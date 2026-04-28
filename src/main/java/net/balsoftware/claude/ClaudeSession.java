package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-level entry point for the Claude coding assistant.
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
    private String cachedSystemPrompt = "";
    private ClaudeRunner runner;

    // Token tracking
    private int totalInputTokens         = 0;
    private int totalOutputTokens        = 0;
    private int totalCacheCreationTokens = 0;
    private int totalCacheReadTokens     = 0;
    private boolean cacheHitObserved     = false;

    // Prompt cache keyed by context + static files hash
    private final Map<Integer, String> promptCache = new ConcurrentHashMap<>();
    private int lastStaticFilesHash = 0;

    private ClaudeSession(Builder builder) {
        this.apiKey = builder.apiKey;
        this.maxTokens = builder.maxTokens;

        SourceRootConfig sourceRootConfig = new SourceRootConfig(builder.sourceRoots);

        this.fileWriter        = new GeneratedFileWriter();
        this.outputRoot        = builder.outputRoot;
        this.model             = builder.model;
        this.conversationStore = new ConversationStore();

        this.sourceFileCollector  = builder.sourceFileCollector != null
                ? builder.sourceFileCollector
                : new SourceFileCollector(sourceRootConfig);

        this.contextFileCollector = builder.contextFileCollector != null
                ? builder.contextFileCollector
                : new ContextFileCollector(builder.contextFilesRoot);

        this.responseParser = new ClaudeResponseParser();

        this.clientFactory = builder.clientFactory != null
                ? builder.clientFactory
                : cfg -> new ClaudeClient(cfg.apiKey(), cfg.maxTokens(), "");
    }

    // ------------------------------------------------------------------ context

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

        cachedSystemPrompt = promptCache.get(contextHash);
        if (cachedSystemPrompt == null) {
            StringBuilder sb = new StringBuilder(ClaudeStructuredResponse.buildSystemPrompt());
            appendFilesToPrompt(sb, "source files", dynamicSourceFiles);
            appendFilesToPrompt(sb, "additional context files", staticContextFiles);
            cachedSystemPrompt = sb.toString();
            promptCache.put(contextHash, cachedSystemPrompt);
        }

        ClaudeClientConfig configWithPrompt = new ClaudeClientConfig(
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
                configWithPrompt,
                dynamicContextClasses
        );
    }

    private void appendFilesToPrompt(StringBuilder sb, String title, List<SourceFile> files) {
        if (!files.isEmpty()) {
            sb.append("\n\nYou have the following ").append(title).append(":\n");
            for (SourceFile f : files) {
                sb.append("\n--- FILE: ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }
    }

// ---------------- messaging ----------------

    public ClaudeStructuredResponseWithTokens ask(String message) throws IOException {
        if (runner == null)
            throw new IllegalStateException("loadContext() must be called first.");

        ClaudeStructuredResponseWithTokens response = runner.runStructured(model, message);

        // Update token tracking
        totalInputTokens         += response.inputTokens();
        totalOutputTokens        += response.outputTokens();
        totalCacheCreationTokens += response.cacheCreationTokens();
        totalCacheReadTokens     += response.cacheReadTokens();
        if (response.cacheReadTokens() > 0) cacheHitObserved = true;

        return response;
    }

    public ClaudeStructuredResponseWithTokens ask(String message, List<Class<?>> contextClasses) throws IOException {
        loadContext(contextClasses);
        return ask(message);
    }


// ---------------- file writing ----------------

    public void writeFiles(ClaudeStructuredResponseWithTokens response) throws IOException {
        fileWriter.writeAll(outputRoot, response);
    }

    public ClaudeStructuredResponseWithTokens askAndWrite(String message) throws IOException {
        ClaudeStructuredResponseWithTokens response = ask(message);
        writeFiles(response);
        return response;
    }

    // ---------------- history & tokens ----------------

    public void resetConversation() { conversationStore.clearTurns(); }

    public void resetAll() {
        conversationStore.clearAll();
        loadedContextClasses = null;
        runner = null;
        cachedSystemPrompt = "";
        lastStaticFilesHash = 0;
        promptCache.clear();
    }

    public int getTurnCount() { return conversationStore.getTurnCount(); }
    public boolean isCacheHitObserved() { return cacheHitObserved; }

    public String getCacheStatus() {
        if (totalCacheCreationTokens > 0 && totalCacheReadTokens == 0) return "CACHE MISS";
        if (totalCacheReadTokens > 0) return "CACHE HIT ✓";
        return "NO CACHE";
    }

    public String tokenSummary() {
        int totalIn = totalInputTokens + totalCacheCreationTokens + totalCacheReadTokens;
        String savingsPct = totalIn > 0
                ? String.format("%.0f%%", (totalCacheReadTokens * 100.0) / totalIn)
                : "n/a";
        return String.format(
                "Total tokens — in: %d, out: %d | cache write: %d, cache read: %d (saved ~%s) [%s]",
                totalInputTokens, totalOutputTokens,
                totalCacheCreationTokens, totalCacheReadTokens,
                savingsPct, getCacheStatus());
    }

    // ---------------- builder ----------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String apiKey;
        private String model              = ClaudeModel.DEFAULT;
        private List<Path> sourceRoots    = List.of(Path.of("src/main/java"));
        private Path outputRoot           = Path.of("generated");
        private Path contextFilesRoot     = Path.of("context-files");
        private int maxTokens             = 4096*2;
        private ClaudeClientFactory clientFactory;
        private SourceFileCollector sourceFileCollector;
        private ContextFileCollector contextFileCollector;

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder sourceRoots(List<Path> roots) { this.sourceRoots = roots; return this; }
        public Builder outputRoot(Path outputRoot) { this.outputRoot = outputRoot; return this; }
        public Builder contextFilesRoot(Path contextFilesRoot) { this.contextFilesRoot = contextFilesRoot; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder clientFactory(ClaudeClientFactory factory) { this.clientFactory = factory; return this; }
        public Builder sourceFileCollector(SourceFileCollector collector) { this.sourceFileCollector = collector; return this; }
        public Builder contextFileCollector(ContextFileCollector collector) { this.contextFileCollector = collector; return this; }

        public ClaudeSession build() {
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalStateException("apiKey must be set");
            return new ClaudeSession(this);
        }
    }

    public List<String> getLoadedContextFiles() {
        if (runner == null) return List.of();

        List<String> sourceFiles = runner.getLastSourceFiles().stream()
                .map(sf -> sf.path().toString())
                .collect(Collectors.toList());

        List<String> contextFiles = runner.getLastContextFiles().stream()
                .map(sf -> sf.path().toString())
                .collect(Collectors.toList());

        sourceFiles.addAll(contextFiles);
        return sourceFiles;
    }

    ClaudeRunner getRunner() { return runner; }
}