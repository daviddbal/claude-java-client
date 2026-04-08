package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * High-level entry point for the Claude coding assistant.
 *
 * <p>Prompt caching is enabled automatically: the system prompt (context files +
 * loaded source classes) is sent as a cached content block. After the first
 * request in a session, Anthropic serves the system-prompt tokens from cache at
 * ~10% of normal cost.
 *
 * <pre>{@code
 * ClaudeSession session = ClaudeSession.builder()
 *         .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *         .model("claude-opus-4-5")
 *         .sourceRoots(List.of(Path.of("src/main/java")))
 *         .outputRoot(Path.of("generated"))
 *         .contextFilesRoot(Path.of("context-files"))
 *         .maxTokens(4096)
 *         .build();
 *
 * session.loadContext(List.of(Foo.class, Bar.class));
 * ClaudeResponse r = session.ask("Add a toString() to Foo");
 * session.writeFiles(r);
 * System.out.println(session.tokenSummary());
 * }</pre>
 */
public class ClaudeSession {

    private final ClaudeRunner runner;
    private final GeneratedFileWriter fileWriter;
    private final Path outputRoot;
    private final String model;
    private final ConversationStore conversationStore;

    // Cumulative token tracking
    private int totalInputTokens         = 0;
    private int totalOutputTokens        = 0;
    private int totalCacheCreationTokens = 0;
    private int totalCacheReadTokens     = 0;

    private ClaudeSession(Builder builder) {
        SourceRootConfig sourceRootConfig = new SourceRootConfig(builder.sourceRoots);
        this.conversationStore = new ConversationStore();
        this.runner = new ClaudeRunner(
                new ClaudeClient(builder.apiKey, builder.maxTokens),
                new SourceFileCollector(sourceRootConfig),
                new ContextFileCollector(builder.contextFilesRoot),
                conversationStore,
                new ClaudeResponseParser()
        );
        this.fileWriter = new GeneratedFileWriter();
        this.outputRoot = builder.outputRoot;
        this.model      = builder.model;
    }

    // ------------------------------------------------------------------ context

    /** Loads source files into the system prompt. Skips if context is unchanged. */
    public void loadContext(List<Class<?>> contextClasses) throws IOException {
        runner.setContext(contextClasses);
    }

    // ------------------------------------------------------------------ messaging

    /** Send a message using context already loaded via {@link #loadContext}. */
    public ClaudeResponse ask(String message) throws IOException {
        ClaudeResponse r = runner.run(model, message);
        totalInputTokens         += r.inputTokens();
        totalOutputTokens        += r.outputTokens();
        totalCacheCreationTokens += r.cacheCreationTokens();
        totalCacheReadTokens     += r.cacheReadTokens();
        return r;
    }

    /**
     * Convenience: load context and ask in one call.
     * Context is only rebuilt if the class list has changed.
     */
    public ClaudeResponse ask(String message, List<Class<?>> contextClasses) throws IOException {
        loadContext(contextClasses);
        return ask(message);
    }

    // ------------------------------------------------------------------ file writing

    /** Write generated files from a response to {@code outputRoot}. */
    public void writeFiles(ClaudeResponse response) throws IOException {
        fileWriter.writeAll(outputRoot, response);
    }

    /** Convenience: ask and immediately write any returned files. */
    public ClaudeResponse askAndWrite(String message) throws IOException {
        ClaudeResponse response = ask(message);
        writeFiles(response);
        return response;
    }

    // ------------------------------------------------------------------ history

    /** Clear turn history while keeping the loaded context in the system prompt. */
    public void resetConversation() {
        conversationStore.clearTurns();
    }

    /** Clear everything — context and history. */
    public void resetAll() {
        conversationStore.clearAll();
    }

    /** How many user/assistant messages are currently in history. */
    public int getTurnCount() {
        return conversationStore.getTurnCount();
    }

    // ------------------------------------------------------------------ token tracking

    /**
     * Human-readable token summary including cache efficiency.
     *
     * <p>Example output:
     * <pre>
     * Total tokens — in: 1200, out: 340 | cache write: 980, cache read: 2940 (saved ~75%)
     * </pre>
     */
    public String tokenSummary() {
        int totalIn = totalInputTokens + totalCacheCreationTokens + totalCacheReadTokens;
        String savingsPct = totalIn > 0
                ? String.format("%.0f%%", (totalCacheReadTokens * 100.0) / totalIn)
                : "n/a";
        return String.format(
                "Total tokens — in: %d, out: %d | cache write: %d, cache read: %d (saved ~%s)",
                totalInputTokens, totalOutputTokens,
                totalCacheCreationTokens, totalCacheReadTokens,
                savingsPct);
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
}