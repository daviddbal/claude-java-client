package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-level API for Claude sessions.
 * Coordinates context management, execution, response caching, token tracking, and file I/O.
 * Delegates core responsibilities to specialized classes.
 *
 * <p><strong>Not thread-safe.</strong> A session and its backing stores hold mutable,
 * unsynchronized state; use a single session from one thread at a time.
 */
public class ClaudeSession {

    private final GeneratedFileWriter fileWriter;
    private final Path outputRoot;
    private final String model;

    // When true, generated file contents (not just paths) are kept in conversation history
    // so follow-up turns can reference previously generated code. See Builder.
    private final boolean includeFileContentInHistory;
    // When true, each response is logged to stdout via ClaudeLogger. Off by default so the
    // library stays quiet when embedded; the CLI does its own printing.
    private final boolean logResponses;

    private final ClaudeContextManager contextManager;
    private final ConversationStore conversationStore;
    private final ClaudeResponseParser responseParser;
    private final ClaudeTokenTracker tokenTracker;

    private static final int MAX_CACHED_RESPONSES = 100;

    // Response cache: key = (system prompt + conversation history + message), value = cached response.
    // The key MUST include the conversation history: the same message asked in a different
    // conversation state is a different request and must not return an earlier answer.
    // Bounded LRU so a long-running session can't grow the cache without limit.
    private final Map<CacheKey, ClaudeStructuredResponseWithTokens> responseCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, ClaudeStructuredResponseWithTokens> eldest) {
                    return size() > MAX_CACHED_RESPONSES;
                }
            };

    /**
     * Composite, value-equality cache key. Using the full content (not a hash code) avoids
     * the collisions that an {@code int}-hash key would silently produce.
     */
    private record CacheKey(String systemPrompt, List<String> history, String message) {}
    
    // Track last response
    private ClaudeStructuredResponseWithTokens lastResponse;

    // -------- CONSTRUCTOR --------

    private ClaudeSession(Builder builder) {
        this.outputRoot = builder.outputRoot;
        this.model = builder.model;
        this.includeFileContentInHistory = builder.includeFileContentInHistory;
        this.logResponses = builder.logResponses;
        this.fileWriter = new GeneratedFileWriter();
        this.conversationStore = new ConversationStore();
        this.responseParser = new ClaudeResponseParser();
        this.tokenTracker = new ClaudeTokenTracker();
        this.lastResponse = null;

        SourceRootConfig sourceRootConfig = new SourceRootConfig(builder.sourceRoots);

        SourceFileCollector sourceFileCollector = builder.sourceFileCollector != null
                ? builder.sourceFileCollector
                : new SourceFileCollector(sourceRootConfig);

        ContextFileCollector contextFileCollector = builder.contextFileCollector != null
                ? builder.contextFileCollector
                : new ContextFileCollector(builder.contextFilesRoot);

        ClaudeClientFactory clientFactory = builder.clientFactory != null
                ? builder.clientFactory
                : cfg -> new OKHttpClaudeClient(cfg.apiKey(), cfg.maxTokens(), "");

        this.contextManager = new ClaudeContextManager(
                clientFactory,
                sourceFileCollector,
                contextFileCollector,
                conversationStore,
                responseParser,
                builder.apiKey,
                builder.maxTokens
        );
    }

    // -------- CONTEXT LOADING --------

    /**
     * Loads context from dynamic classes and static files.
     */
    public void loadContext(List<Class<?>> dynamicContextClasses) throws IOException {
        contextManager.loadContext(dynamicContextClasses);
    }

    // -------- ASK / EXECUTE --------

    /**
     * Asks Claude a question and returns the structured response.
     */
    public ClaudeStructuredResponseWithTokens ask(String message) throws IOException {
        return ask(message, (java.util.function.Consumer<String>) null);
    }

    /**
     * Asks Claude a question, streaming text chunks to {@code onTextDelta} as they arrive, and
     * returns the final structured response. Streaming happens only on a cache miss (a cache
     * hit returns immediately without invoking the callback).
     */
    public ClaudeStructuredResponseWithTokens askStreaming(String message, java.util.function.Consumer<String> onTextDelta)
            throws IOException {
        return ask(message, onTextDelta);
    }

    private ClaudeStructuredResponseWithTokens ask(String message, java.util.function.Consumer<String> onTextDelta)
            throws IOException {
        if (contextManager.getExecutor() == null) {
            throw new IllegalStateException("loadContext() must be called first.");
        }

        // Check response cache
        CacheKey cacheKey = buildCacheKey(contextManager.getRunner().getCachedSystemPrompt(), message);
        ClaudeStructuredResponseWithTokens cached = responseCache.get(cacheKey);

        ClaudeStructuredResponseWithTokens response;

        if (cached != null) {
            // Cache hit: return response with cacheReadTokens set
            response = new ClaudeStructuredResponseWithTokens(
                    cached.structured(),
                    cached.inputTokens(),
                    cached.outputTokens(),
                    0,                      // cacheCreationTokens
                    cached.inputTokens()    // cacheReadTokens (simulated)
            );
            tokenTracker.accumulate(response);
        } else {
            // Execute and cache (streaming when a callback is supplied)
            response = contextManager.getExecutor().execute(model, message, onTextDelta);
            responseCache.put(cacheKey, response);
            tokenTracker.accumulate(response);

            // Create and store turn (ONLY ONCE)
            ClaudeTurn turn = new ClaudeTurn(
                    message,
                    buildHistorySummary(response.structured()),
                    response.structured(),
                    response.inputTokens(),
                    response.outputTokens(),
                    response.cacheCreationTokens(),
                    response.cacheReadTokens()
            );
            conversationStore.addTurn(turn);
        }

        // Store as last response
        this.lastResponse = response;

        // Log the response (opt-in; off by default for quiet library use)
        if (logResponses) {
            ClaudeLogger.logResponse(model, message, new OKHttpClaudeClient.RawResponse(
                    response.structured().description() != null ? response.structured().description() : "[No description]",
                    response.inputTokens(),
                    response.outputTokens(),
                    response.cacheCreationTokens(),
                    response.cacheReadTokens()
            ));
        }

        return response;
    }

    /**
     * Asks Claude a question with explicit context classes.
     */
    public ClaudeStructuredResponseWithTokens ask(String message, List<Class<?>> contextClasses) throws IOException {
        loadContext(contextClasses);
        return ask(message);
    }

    /**
     * Builds the assistant message stored in conversation history.
     *
     * <p>By default this includes each generated file's full content so follow-up turns can
     * reference the code that was produced. When {@code includeFileContentInHistory} is
     * disabled, only the file paths are recorded (the original token-saving summary).
     */
    private String buildHistorySummary(ClaudeStructuredResponse response) {
        String description = response.description() != null ? response.description() : "";

        if (!response.hasFiles()) {
            return description;
        }

        if (!includeFileContentInHistory) {
            String filePaths = response.files().stream()
                    .map(ClaudeStructuredResponse.FileItem::path)
                    .toList()
                    .toString();
            return description + " [files: " + filePaths + "]";
        }

        StringBuilder sb = new StringBuilder(description);
        for (ClaudeStructuredResponse.FileItem file : response.files()) {
            sb.append("\n\n--- ").append(file.path()).append(" ---\n")
                    .append(file.content() != null ? file.content() : "");
        }
        return sb.toString();
    }

    /**
     * Builds the response-cache key from the system prompt, the current conversation
     * history, and the new message. History is included so a repeated message in a
     * changed conversation is treated as a distinct request.
     */
    private CacheKey buildCacheKey(String systemPrompt, String message) {
        List<String> history = new ArrayList<>();
        for (ClaudeTurn turn : conversationStore.getTurns()) {
            history.add(turn.getUserMessage());
            history.add(turn.getAssistantMessage());
        }
        return new CacheKey(systemPrompt, List.copyOf(history), message);
    }

    // -------- FILE I/O --------

    /**
     * Writes generated files from a response to disk.
     */
    public void writeFiles(ClaudeStructuredResponseWithTokens response) throws IOException {
        fileWriter.writeAll(outputRoot, response);
    }

    /**
     * Asks Claude and writes the response files in one call.
     */
    public ClaudeStructuredResponseWithTokens askAndWrite(String message) throws IOException {
        ClaudeStructuredResponseWithTokens response = ask(message);
        writeFiles(response);
        return response;
    }

    // -------- RESET --------

    /**
     * Resets all session state: conversation, context, caches, and tokens.
     */
    public void resetAll() {
        conversationStore.clearAll();
        contextManager.reset();
        responseCache.clear();
        tokenTracker.reset();
        lastResponse = null;
    }

    /**
     * Resets only the conversation history (preserves context and caches).
     */
    public void resetConversation() {
        conversationStore.clearTurns();
    }

    // -------- METRICS / DIAGNOSTICS --------

    /**
     * Returns the number of turns.
     */
    public int getTurnCount() {
        return conversationStore.getTurns().size();
    }

    /**
     * Returns whether a cache hit has been observed.
     */
    public boolean isCacheHitObserved() {
        return tokenTracker.isCacheHitObserved();
    }

    /**
     * Returns the cache status string.
     */
    public String getCacheStatus() {
        return tokenTracker.getCacheStatus();
    }

    /**
     * Returns a human-readable token summary.
     */
    public String tokenSummary() {
        return tokenTracker.tokenSummary();
    }

    /**
     * Returns the list of loaded context files.
     */
    public List<String> getLoadedContextFiles() {
        return contextManager.getLoadedContextFiles();
    }

    /**
     * Returns the conversation history as a list of user messages only.
     */
    public List<String> getConversationHistory() {
        return conversationStore.getTurns().stream()
                .map(ClaudeTurn::getUserMessage)
                .toList();
    }

    // -------- RESTORE --------

    /**
     * Rehydrates this session from a persisted snapshot, resolving the snapshot's
     * stored context class names via {@link Class#forName(String)}.
     *
     * @throws IllegalArgumentException if a stored context class cannot be resolved
     */
    public void restore(SessionSnapshot snapshot) throws IOException {
        restore(snapshot, resolveContextClasses(snapshot.loadedContextClassNames()));
    }

    /**
     * Rehydrates this session from a persisted snapshot using the caller-supplied
     * context classes. After this call, conversation turns and accumulated token
     * totals match the saved session, and the next {@link #ask(String)} replays the
     * restored turns as conversation history.
     */
    public void restore(SessionSnapshot snapshot, List<Class<?>> contextClasses) throws IOException {
        resetAll();
        loadContext(contextClasses);

        List<ClaudeTurn> turns = snapshot.turns().stream()
                .map(SerializableTurn::toClaudeTurn)
                .toList();
        conversationStore.restoreTurns(turns);

        tokenTracker.restoreFromSnapshot(
                snapshot.totalInputTokens(),
                snapshot.totalOutputTokens(),
                snapshot.totalCacheCreationTokens(),
                snapshot.totalCacheReadTokens()
        );

        if (!turns.isEmpty()) {
            ClaudeTurn last = turns.get(turns.size() - 1);
            this.lastResponse = new ClaudeStructuredResponseWithTokens(
                    last.structured(),
                    last.inputTokens(),
                    last.outputTokens(),
                    last.cacheCreationTokens(),
                    last.cacheReadTokens()
            );
        }
    }

    private static List<Class<?>> resolveContextClasses(List<String> classNames) {
        List<Class<?>> classes = new ArrayList<>();
        for (String name : classNames) {
            try {
                classes.add(Class.forName(name));
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Cannot resolve context class: " + name, e);
            }
        }
        return classes;
    }

    // -------- PERSISTENCE HELPERS (REQUIRED BY SessionPersistence) --------

    public List<SerializableTurn> getConversationTurns() {
        return conversationStore.getTurns().stream()
                .map(t -> new SerializableTurn(
                        t.getUserMessage(),
                        t.getAssistantMessage(),
                        t.structured(),
                        t.inputTokens(),
                        t.outputTokens(),
                        t.cacheCreationTokens(),
                        t.cacheReadTokens()))
                .toList();
    }

    public List<String> getLoadedContextClassNames() {
        return contextManager.getLoadedContextClasses().stream()
                .map(Class::getName)
                .toList();
    }

    public String getSystemPrompt() {
        return contextManager.getRunner().getCachedSystemPrompt();
    }

    public int getTotalInputTokens() { return tokenTracker.getTotalInputTokens(); }
    public int getTotalOutputTokens() { return tokenTracker.getTotalOutputTokens(); }
    public int getTotalCacheCreationTokens() { return tokenTracker.getTotalCacheCreationTokens(); }
    public int getTotalCacheReadTokens() { return tokenTracker.getTotalCacheReadTokens(); }

    /**
     * Returns the last response received from Claude.
     */
    public ClaudeStructuredResponseWithTokens getLastResponse() {
        return lastResponse;
    }

    // -------- BUILDER --------

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String model = ClaudeModel.defaultModel().id();
        private List<Path> sourceRoots = List.of(Path.of("src/main/java"));
        private Path outputRoot = Path.of("generated");
        private Path contextFilesRoot = Path.of("context-files");
        private int maxTokens = 8192;
        private boolean includeFileContentInHistory = true;
        private boolean logResponses = false;

        private ClaudeClientFactory clientFactory;
        private SourceFileCollector sourceFileCollector;
        private ContextFileCollector contextFileCollector;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder sourceRoots(List<Path> roots) {
            this.sourceRoots = roots;
            return this;
        }

        public Builder outputRoot(Path outputRoot) {
            this.outputRoot = outputRoot;
            return this;
        }

        public Builder contextFilesRoot(Path root) {
            this.contextFilesRoot = root;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Whether generated file contents are kept in conversation history so follow-up turns
         * can reference prior generated code. Defaults to {@code true}; set {@code false} to
         * record only file paths (less context, fewer tokens).
         */
        public Builder includeFileContentInHistory(boolean include) {
            this.includeFileContentInHistory = include;
            return this;
        }

        /** Whether each response is logged to stdout. Defaults to {@code false}. */
        public Builder logResponses(boolean logResponses) {
            this.logResponses = logResponses;
            return this;
        }

        public Builder clientFactory(ClaudeClientFactory f) {
            this.clientFactory = f;
            return this;
        }

        public Builder sourceFileCollector(SourceFileCollector c) {
            this.sourceFileCollector = c;
            return this;
        }

        public Builder contextFileCollector(ContextFileCollector c) {
            this.contextFileCollector = c;
            return this;
        }

        public ClaudeSession build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("apiKey must be set");
            }
            return new ClaudeSession(this);
        }
    }

    // -------- TEST ACCESS --------

    ClaudeRunner getRunner() {
        return contextManager.getRunner();
    }
}
