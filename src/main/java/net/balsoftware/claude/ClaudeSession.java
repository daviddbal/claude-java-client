package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * High-level entry point for the Claude coding assistant.
 *
 * Prompt caching is enabled automatically: the system prompt (context files +
 * loaded source classes) is sent as a cached content block. After the first
 * request in a session, Anthropic serves the system-prompt tokens from cache at
 * ~10% of normal cost.
 */
public class ClaudeSession {

    private final GeneratedFileWriter fileWriter;
    private final Path outputRoot;
    private final String model;
    private final ConversationStore conversationStore;
    private final ClaudeClientFactory clientFactory; // NEW!
    private final SourceFileCollector sourceFileCollector;
    private final ContextFileCollector contextFileCollector;
    private final ClaudeResponseParser responseParser;

    private List<Class<?>> loadedContextClasses = null;
    private String cachedSystemPrompt = "";
    private ClaudeRunner runner; // Will be rebuilt on context load

    // Cumulative token tracking
    private int totalInputTokens         = 0;
    private int totalOutputTokens        = 0;
    private int totalCacheCreationTokens = 0;
    private int totalCacheReadTokens     = 0;

    private boolean cacheHitObserved = false;

    private ClaudeSession(Builder builder) {
        SourceRootConfig sourceRootConfig = new SourceRootConfig(builder.sourceRoots);
        this.conversationStore    = new ConversationStore();
        this.sourceFileCollector  = new SourceFileCollector(sourceRootConfig);
        this.contextFileCollector = new ContextFileCollector(builder.contextFilesRoot);
        this.responseParser       = new ClaudeResponseParser();
        this.fileWriter           = new GeneratedFileWriter();
        this.outputRoot           = builder.outputRoot;
        this.model                = builder.model;
        this.clientFactory        = new ClaudeClientFactory(builder.apiKey, builder.maxTokens); // NEW!
    }

    // ------------------------------------------------------------------ context

    /** Loads source files into the system prompt. Skips if context is unchanged. */
    public void loadContext(List<Class<?>> contextClasses) throws IOException {
        if (contextClasses.equals(loadedContextClasses)) return;
        loadedContextClasses = contextClasses;

        // Build static system prompt with context (from ClaudeRunner logic)
        List<SourceFile> contextDirFiles = contextFileCollector.collect();
        List<SourceFile> sourceFiles = sourceFileCollector.collect(contextClasses);

        StringBuilder sb = new StringBuilder(ClaudeRunner.BASE_SYSTEM_PROMPT);

        if (!sourceFiles.isEmpty()) {
            sb.append("\n\nYou have the following source files as context:\n");
            for (SourceFile f : sourceFiles) {
                sb.append("\n--- FILE: ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }

        if (!contextDirFiles.isEmpty()) {
            sb.append("\n\nYou have the following additional context files:\n");
            sb.append(buildContextSection(contextDirFiles));
        }

        this.cachedSystemPrompt = sb.toString();

        // REPLACE ClaudeClient and runner with updated system prompt
        ClaudeClient client = clientFactory.create(cachedSystemPrompt);
        this.runner = new ClaudeRunner(
                client,
                sourceFileCollector,
                contextFileCollector,
                conversationStore,
                responseParser
        );
    }

    private String buildContextSection(List<SourceFile> files) {
        Path contextRoot = contextFileCollector.getContextRoot();
        StringBuilder sb = new StringBuilder();
        for (SourceFile f : files) {
            sb.append("\n--- FILE: ").append(f.path()).append(" ---\n");
            sb.append(f.content()).append("\n");
        }
        return sb.toString();
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

    // ------------------------------------------------------------------ messaging

    public ClaudeResponse ask(String message) throws IOException {
        if (runner == null)
            throw new IllegalStateException("You must call loadContext() before ask.");

        ClaudeResponse r = runner.run(model, message);
        totalInputTokens         += r.inputTokens();
        totalOutputTokens        += r.outputTokens();
        totalCacheCreationTokens += r.cacheCreationTokens();
        totalCacheReadTokens     += r.cacheReadTokens();

        if (r.cacheReadTokens() > 0) cacheHitObserved = true;
        return r;
    }

    public ClaudeResponse ask(String message, List<Class<?>> contextClasses) throws IOException {
        loadContext(contextClasses);
        return ask(message);
    }

    // ------------------------------------------------------------------ file writing

    public void writeFiles(ClaudeResponse response) throws IOException {
        fileWriter.writeAll(outputRoot, response);
    }

    public ClaudeResponse askAndWrite(String message) throws IOException {
        ClaudeResponse response = ask(message);
        writeFiles(response);
        return response;
    }

    // ------------------------------------------------------------------ history

    public void resetConversation() {
        conversationStore.clearTurns();
    }

    public void resetAll() {
        conversationStore.clearAll();
        loadedContextClasses = null;
        runner = null;
        cachedSystemPrompt = "";
    }

    public int getTurnCount() {
        return conversationStore.getTurnCount();
    }

    // ------------------------------------------------------------------ token tracking & cache status

    public boolean isCacheHitObserved() {
        return cacheHitObserved;
    }

    public String getCacheStatus() {
        if (totalCacheCreationTokens > 0 && totalCacheReadTokens == 0) {
            return "CACHE MISS — cache written, not yet used";
        } else if (totalCacheReadTokens > 0) {
            return "CACHE HIT ✓ — serving from prompt cache";
        } else {
            return "NO CACHE";
        }
    }

    public String tokenSummary() {
        int totalIn = totalInputTokens + totalCacheCreationTokens + totalCacheReadTokens;
        String savingsPct = totalIn > 0
                ? String.format("%.0f%%", (totalCacheReadTokens * 100.0) / totalIn)
                : "n/a";
        String cacheStatus = getCacheStatus();
        return String.format(
                "Total tokens — in: %d, out: %d | cache write: %d, cache read: %d (saved ~%s) [%s]",
                totalInputTokens, totalOutputTokens,
                totalCacheCreationTokens, totalCacheReadTokens,
                savingsPct, cacheStatus);
    }

    public int getTotalInputTokens()         { return totalInputTokens; }
    public int getTotalOutputTokens()        { return totalOutputTokens; }
    public int getTotalCacheCreationTokens() { return totalCacheCreationTokens; }
    public int getTotalCacheReadTokens()     { return totalCacheReadTokens; }

    // ------------------------------------------------------------------ builder

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String apiKey;
        private String model              = ClaudeModel.DEFAULT;
        private List<Path> sourceRoots    = List.of(Path.of("src/main/java"));
        private Path outputRoot           = Path.of("generated");
        private Path contextFilesRoot     = Path.of("context-files");
        private int maxTokens             = 4096*2;

        public Builder apiKey(String apiKey)                      { this.apiKey = apiKey;                     return this; }
        public Builder model(String model)                        { this.model = model;                       return this; }
        public Builder sourceRoots(List<Path> roots)              { this.sourceRoots = roots;                 return this; }
        public Builder outputRoot(Path outputRoot)                { this.outputRoot = outputRoot;             return this; }
        public Builder contextFilesRoot(Path contextFilesRoot)    { this.contextFilesRoot = contextFilesRoot; return this; }
        public Builder maxTokens(int maxTokens)                   { this.maxTokens = maxTokens;               return this; }

        public ClaudeSession build() {
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalStateException("apiKey must be set");
            return new ClaudeSession(this);
        }
    }

    /**
     * Factory object to build ClaudeClient with a fixed prompt and tokens.
     * Used to "swap in" new client whenever prompt/context changes.
     */
    public static class ClaudeClientFactory {
        private final String apiKey;
        private final int maxTokens;
        public ClaudeClientFactory(String apiKey, int maxTokens) {
            this.apiKey = apiKey;
            this.maxTokens = maxTokens;
        }
        public ClaudeClient create(String systemPrompt) {
            return new ClaudeClient(apiKey, maxTokens, systemPrompt);
        }
    }
}