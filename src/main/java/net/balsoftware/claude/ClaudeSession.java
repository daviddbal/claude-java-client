package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * High-level API for Claude sessions.
 * Coordinates context management, execution, response caching, token tracking, and file I/O.
 * Delegates core responsibilities to specialized classes.
 */
public class ClaudeSession {

    private final GeneratedFileWriter fileWriter;
    private final Path outputRoot;
    private final String model;

    private final ClaudeContextManager contextManager;
    private final ConversationStore conversationStore;
    private final ClaudeResponseParser responseParser;
    private final ClaudeTokenTracker tokenTracker;

    // Response cache: key = hash(contextHash, message), value = cached response
    private final Map<Integer, ClaudeStructuredResponseWithTokens> responseCache = new HashMap<>();
    
    // Track last response
    private ClaudeStructuredResponseWithTokens lastResponse;

    // -------- CONSTRUCTOR --------

    private ClaudeSession(Builder builder) {
        this.outputRoot = builder.outputRoot;
        this.model = builder.model;
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
        if (contextManager.getExecutor() == null) {
            throw new IllegalStateException("loadContext() must be called first.");
        }

        // Check response cache
        int cacheKey = Objects.hash(contextManager.getRunner().getCachedSystemPrompt(), message);
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
            // Execute and cache
            response = contextManager.getExecutor().execute(model, message);
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

        // Log the response
        ClaudeLogger.logResponse(model, message, new OKHttpClaudeClient.RawResponse(
                response.structured().description() != null ? response.structured().description() : "[No description]",
                response.inputTokens(),
                response.outputTokens(),
                response.cacheCreationTokens(),
                response.cacheReadTokens()
        ));

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
     * Builds a summary string from the response for conversation history.
     */
    private String buildHistorySummary(ClaudeStructuredResponse response) {
        if (!response.hasFiles()) {
            return response.description() != null ? response.description() : "";
        }

        String filePaths = response.files().stream()
                .map(ClaudeStructuredResponse.FileItem::path)
                .toList()
                .toString();

        return (response.description() != null ? response.description() : "") + " [files: " + filePaths + "]";
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

    // -------- PERSISTENCE HELPERS (REQUIRED BY SessionPersistence) --------

    public List<SerializableTurn> getConversationTurns() {
        return conversationStore.getTurns().stream()
                .map(t -> new SerializableTurn(t.getUserMessage(), t.getAssistantMessage()))
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
