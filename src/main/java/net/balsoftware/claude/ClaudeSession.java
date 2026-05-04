package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * File-centric ClaudeSession:
 * - files are the only real output artifact
 * - description is optional metadata
 * - no payload/content/explanation fields
 */
public class ClaudeSession {

    private final GeneratedFileWriter fileWriter;
    private final Path outputRoot;
    private final String model;

    private final ConversationStore conversationStore;
    private final ClaudeClientFactory clientFactory;
    private final SourceFileCollector sourceFileCollector;
    private final ContextFileCollector contextFileCollector;
    private final ClaudeResponseParser responseParser;

    private final String apiKey;
    private final int maxTokens;

    private List<Class<?>> loadedContextClasses = null;
    private ClaudeRunner runner;

    // ---------------- TOKEN TRACKING ----------------

    private int totalInputTokens = 0;
    private int totalOutputTokens = 0;
    private int totalCacheCreationTokens = 0;
    private int totalCacheReadTokens = 0;
    private boolean cacheHitObserved = false;

    // ---------------- CACHE ----------------

    private final Map<Integer, String> promptCache = new ConcurrentHashMap<>();
    private final Map<Integer, ClaudeStructuredResponseWithTokens> responseCache = new ConcurrentHashMap<>();
    private int lastStaticFilesHash = 0;

    // ---------------- CONSTRUCTOR ----------------

    private ClaudeSession(Builder builder) {
        this.apiKey = builder.apiKey;
        this.maxTokens = builder.maxTokens;
        this.outputRoot = builder.outputRoot;
        this.model = builder.model;

        this.fileWriter = new GeneratedFileWriter();
        this.conversationStore = new ConversationStore();
        this.responseParser = new ClaudeResponseParser();

        SourceRootConfig sourceRootConfig = new SourceRootConfig(builder.sourceRoots);

        this.sourceFileCollector = builder.sourceFileCollector != null
                ? builder.sourceFileCollector
                : new SourceFileCollector(sourceRootConfig);

        this.contextFileCollector = builder.contextFileCollector != null
                ? builder.contextFileCollector
                : new ContextFileCollector(builder.contextFilesRoot);

        this.clientFactory = builder.clientFactory != null
                ? builder.clientFactory
                : cfg -> new OKHttpClaudeClient(cfg.apiKey(), cfg.maxTokens(), "");
    }

    // ---------------- CONTEXT ----------------

    public void loadContext(List<Class<?>> dynamicContextClasses) throws IOException {

        List<SourceFile> staticContextFiles = contextFileCollector.collect();
        List<SourceFile> dynamicSourceFiles = sourceFileCollector.collect(dynamicContextClasses);

        int contextHash = Objects.hash(dynamicContextClasses, staticContextFiles);

        if (Objects.equals(dynamicContextClasses, loadedContextClasses)
                && contextHash == lastStaticFilesHash
                && runner != null) {
            return;
        }

        loadedContextClasses = dynamicContextClasses;
        lastStaticFilesHash = contextHash;

        String cachedSystemPrompt = promptCache.get(contextHash);

        if (cachedSystemPrompt == null) {
            cachedSystemPrompt = buildSystemPrompt(dynamicSourceFiles, staticContextFiles);
            promptCache.put(contextHash, cachedSystemPrompt);
        }

        ClaudeClientConfig config = new ClaudeClientConfig(
                apiKey,
                maxTokens,
                true,
                cachedSystemPrompt
        );

        runner = new ClaudeRunner(
                clientFactory,
                sourceFileCollector,
                contextFileCollector,
                conversationStore,
                responseParser,
                config,
                dynamicContextClasses
        );
    }

    // ---------------- SYSTEM PROMPT (INLINE) ----------------

    private String buildSystemPrompt(List<SourceFile> sourceFiles,
                                     List<SourceFile> contextFiles) {

        StringBuilder sb = new StringBuilder();

        sb.append(ClaudeSystemPrompt.build());

        if (!sourceFiles.isEmpty()) {
            sb.append("\n\nSOURCE FILES:\n");
            for (SourceFile f : sourceFiles) {
                sb.append("\n--- ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }

        if (!contextFiles.isEmpty()) {
            sb.append("\n\nCONTEXT FILES:\n");
            for (SourceFile f : contextFiles) {
                sb.append("\n--- ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }

        return sb.toString();
    }

    // ---------------- ASK ----------------

    public ClaudeStructuredResponseWithTokens ask(String message) throws IOException {
        if (runner == null) {
            throw new IllegalStateException("loadContext() must be called first.");
        }

        int key = Objects.hash(lastStaticFilesHash, message);

        ClaudeStructuredResponseWithTokens cached = responseCache.get(key);
        ClaudeStructuredResponseWithTokens response;

        if (cached != null) {
            // Return a "cache hit" view of the response
            response = new ClaudeStructuredResponseWithTokens(
                    cached.structured(),
                    cached.inputTokens(),
                    cached.outputTokens(),
                    0,                      // cacheCreationTokens
                    cached.inputTokens()    // cacheReadTokens
            );
            totalCacheReadTokens += response.cacheReadTokens();
            cacheHitObserved = true;
        } else {
            conversationStore.addUserMessage(message);

            response = runner.runStructured(model, message);

            responseCache.put(key, response);

            // token accounting
            totalInputTokens += response.inputTokens();
            totalOutputTokens += response.outputTokens();
            totalCacheCreationTokens += response.cacheCreationTokens();
        }

        // ---------------- LOGGING ----------------
        ClaudeLogger.logResponse(model, message, new OKHttpClaudeClient.RawResponse(
                response.structured().description() != null ? response.structured().description() : "[No description]",
                response.inputTokens(),
                response.outputTokens(),
                response.cacheCreationTokens(),
                response.cacheReadTokens()
        ));

        return response;
    }

    public ClaudeStructuredResponseWithTokens ask(String message,
                                                  List<Class<?>> contextClasses) throws IOException {
        loadContext(contextClasses);
        return ask(message);
    }

    // ---------------- FILE OUTPUT ----------------

    public void writeFiles(ClaudeStructuredResponseWithTokens response) throws IOException {
        fileWriter.writeAll(outputRoot, response);
    }

    public ClaudeStructuredResponseWithTokens askAndWrite(String message) throws IOException {
        ClaudeStructuredResponseWithTokens response = ask(message);
        writeFiles(response);
        return response;
    }

    // ---------------- RESET ----------------

    public void resetAll() {
        conversationStore.clearAll();
        loadedContextClasses = null;
        runner = null;

        promptCache.clear();
        responseCache.clear();

        lastStaticFilesHash = 0;

        totalInputTokens = 0;
        totalOutputTokens = 0;
        totalCacheCreationTokens = 0;
        totalCacheReadTokens = 0;
        cacheHitObserved = false;
    }

    public int getTurnCount() { return conversationStore.getTurnCount(); }
    public boolean isCacheHitObserved() { return cacheHitObserved; }

    public void resetConversation() {
        conversationStore.clearTurns();
    }

    // ---------------- METRICS ----------------

    public String getCacheStatus() {
        if (totalCacheReadTokens > 0) return "CACHE HIT ✓";
        if (totalCacheCreationTokens > 0) return "CACHE MISS";
        return "NO CACHE";
    }

    public String tokenSummary() {
        int totalIn = totalInputTokens + totalCacheCreationTokens + totalCacheReadTokens;

        String savings = totalIn > 0
                ? String.format("%.0f%%", (totalCacheReadTokens * 100.0) / totalIn)
                : "n/a";

        return String.format(
                "tokens in: %d out: %d | cache write: %d read: %d (saved ~%s) [%s]",
                totalInputTokens,
                totalOutputTokens,
                totalCacheCreationTokens,
                totalCacheReadTokens,
                savings,
                getCacheStatus()
        );
    }

    // ---------------- BUILDER ----------------

    public static Builder builder() { return new Builder(); }

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

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder sourceRoots(List<Path> roots) { this.sourceRoots = roots; return this; }
        public Builder outputRoot(Path outputRoot) { this.outputRoot = outputRoot; return this; }
        public Builder contextFilesRoot(Path root) { this.contextFilesRoot = root; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }

        public Builder clientFactory(ClaudeClientFactory f) { this.clientFactory = f; return this; }
        public Builder sourceFileCollector(SourceFileCollector c) { this.sourceFileCollector = c; return this; }
        public Builder contextFileCollector(ContextFileCollector c) { this.contextFileCollector = c; return this; }

        public ClaudeSession build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("apiKey must be set");
            }
            return new ClaudeSession(this);
        }
    }

    // ---------------- CONTEXT INSPECTION ----------------

    public List<String> getLoadedContextFiles() {
        if (runner == null) return List.of();

        List<String> source = runner.getLastSourceFiles().stream()
                .map(f -> f.path().toString())
                .collect(Collectors.toList());

        List<String> context = runner.getLastContextFiles().stream()
                .map(f -> f.path().toString())
                .collect(Collectors.toList());

        source.addAll(context);
        return source;
    }

    ClaudeRunner getRunner() { return runner; }
}