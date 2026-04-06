package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * High-level entry point for the Claude coding assistant.
 *
 * <pre>{@code
 * ClaudeSession session = ClaudeSession.builder()
 *         .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *         .model("claude-opus-4-5")
 *         .sourceRoots(List.of(Path.of("src/main/java")))
 *         .outputRoot(Path.of("generated"))
 *         .maxTokens(4096)
 *         .build();
 *
 * session.loadContext(List.of(Foo.class, Bar.class));
 *
 * ClaudeResponse r1 = session.ask("Add a toString() to Foo");
 * ClaudeResponse r2 = session.ask("Now add equals() and hashCode() too");
 *
 * session.writeFiles(r2);
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
    private int totalInputTokens  = 0;
    private int totalOutputTokens = 0;

    private ClaudeSession(Builder builder) {
        SourceRootConfig sourceRootConfig = new SourceRootConfig(builder.sourceRoots);
        this.conversationStore = new ConversationStore();
        this.runner = new ClaudeRunner(
                new ClaudeClient(builder.apiKey, builder.maxTokens),
                new SourceFileCollector(sourceRootConfig),
                conversationStore,
                new ClaudeResponseParser()
        );
        this.fileWriter = new GeneratedFileWriter();
        this.outputRoot = builder.outputRoot;
        this.model      = builder.model;
    }

    // ------------------------------------------------------------------ context

    /**
     * Loads source files into the system prompt. Skips if the context is unchanged.
     * Does NOT count as a conversation turn.
     */
    public void loadContext(List<Class<?>> contextClasses) throws IOException {
        runner.setContext(contextClasses);
    }

    // ------------------------------------------------------------------ messaging

    /** Send a message using context already loaded via {@link #loadContext}. */
    public ClaudeResponse ask(String message) throws IOException {
        ClaudeResponse r = runner.run(model, message);
        totalInputTokens  += r.inputTokens();
        totalOutputTokens += r.outputTokens();
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

    /** Returns a human-readable summary of total tokens used this session. */
    public String tokenSummary() {
        return String.format("Total tokens — in: %d, out: %d", totalInputTokens, totalOutputTokens);
    }

    public int getTotalInputTokens()  { return totalInputTokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }

    // ------------------------------------------------------------------ builder

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String apiKey;
        private String model           = "claude-opus-4-5";
        private List<Path> sourceRoots = List.of(Path.of("src/main/java"));
        private Path outputRoot        = Path.of("generated");
        private int maxTokens          = 4096;

        public Builder apiKey(String apiKey)          { this.apiKey = apiKey;           return this; }
        public Builder model(String model)            { this.model = model;             return this; }
        public Builder sourceRoots(List<Path> roots)  { this.sourceRoots = roots;       return this; }
        public Builder outputRoot(Path outputRoot)    { this.outputRoot = outputRoot;   return this; }
        public Builder maxTokens(int maxTokens)       { this.maxTokens = maxTokens;     return this; }

        public ClaudeSession build() {
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalStateException("apiKey must be set");
            return new ClaudeSession(this);
        }
    }
}