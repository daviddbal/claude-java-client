package net.balsoftware.claude;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages context loading and caching.
 * Responsibility: collect context, decide when a rebuild is needed, and own the runner.
 *
 * <p>The system prompt itself is built in exactly one place — {@link ClaudeRunner} — which
 * this manager delegates to. This manager only decides <em>whether</em> a new runner is
 * needed (context unchanged → reuse the existing runner and its cached prompt/client).
 */
public class ClaudeContextManager {

    private final ClaudeClientFactory clientFactory;
    private final SourceFileCollector sourceFileCollector;
    private final ContextFileCollector contextFileCollector;
    private final ConversationStore conversationStore;
    private final ClaudeResponseParser responseParser;

    private final String apiKey;
    private final int maxTokens;

    private List<Class<?>> loadedContextClasses = null;
    private ClaudeRunner runner;
    private ClaudeExecutor executor;

    private int lastStaticFilesHash = 0;

    public ClaudeContextManager(
            ClaudeClientFactory clientFactory,
            SourceFileCollector sourceFileCollector,
            ContextFileCollector contextFileCollector,
            ConversationStore conversationStore,
            ClaudeResponseParser responseParser,
            String apiKey,
            int maxTokens
    ) {
        this.clientFactory = clientFactory;
        this.sourceFileCollector = sourceFileCollector;
        this.contextFileCollector = contextFileCollector;
        this.conversationStore = conversationStore;
        this.responseParser = responseParser;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
    }

    /**
     * Loads context from dynamic classes and static files.
     * Rebuilds the runner only if context has changed; otherwise reuses the existing one.
     */
    public void loadContext(List<Class<?>> dynamicContextClasses) throws IOException {
        List<SourceFile> staticContextFiles = contextFileCollector.collect();

        int contextHash = java.util.Objects.hash(dynamicContextClasses, staticContextFiles);

        // Check if we need to rebuild
        if (java.util.Objects.equals(dynamicContextClasses, loadedContextClasses)
                && contextHash == lastStaticFilesHash
                && runner != null) {
            return; // No change, reuse existing runner (and its cached prompt + client)
        }

        loadedContextClasses = dynamicContextClasses;
        lastStaticFilesHash = contextHash;

        // The runner is the single source of truth for the system prompt: it builds the
        // prompt from the collected files. An empty prompt here tells it to build a fresh one.
        ClaudeClientConfig config = new ClaudeClientConfig(apiKey, maxTokens, true, "");

        runner = new ClaudeRunner(
                clientFactory,
                sourceFileCollector,
                contextFileCollector,
                conversationStore,
                responseParser,
                config,
                dynamicContextClasses
        );

        executor = new ClaudeExecutor(runner, responseParser);
    }

    // -------- Getters --------

    public ClaudeExecutor getExecutor() {
        return executor;
    }

    public ClaudeRunner getRunner() {
        return runner;
    }

    public List<String> getLoadedContextFiles() {
        if (runner == null) return List.of();

        List<String> source = runner.getLastSourceFiles().stream()
                .map(f -> f.path().toString())
                .toList();

        List<String> context = runner.getLastContextFiles().stream()
                .map(f -> f.path().toString())
                .toList();

        List<String> combined = new ArrayList<>(source);
        combined.addAll(context);
        return combined;
    }

    /**
     * Returns the currently loaded dynamic context classes.
     */
    public List<Class<?>> getLoadedContextClasses() {
        return this.loadedContextClasses != null ? this.loadedContextClasses : List.of();
    }

    public void reset() {
        loadedContextClasses = null;
        runner = null;
        executor = null;
        lastStaticFilesHash = 0;
    }
}
