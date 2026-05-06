package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages context loading and caching.
 * Responsibility: Load context, cache system prompts, manage runners.
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

    private final Map<Integer, String> promptCache = new HashMap<>();
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
     * Rebuilds runner only if context has changed.
     */
    public void loadContext(List<Class<?>> dynamicContextClasses) throws IOException {
        List<SourceFile> staticContextFiles = contextFileCollector.collect();
        List<SourceFile> dynamicSourceFiles = sourceFileCollector.collect(dynamicContextClasses);

        int contextHash = java.util.Objects.hash(dynamicContextClasses, staticContextFiles);

        // Check if we need to rebuild
        if (java.util.Objects.equals(dynamicContextClasses, loadedContextClasses)
                && contextHash == lastStaticFilesHash
                && runner != null) {
            return; // No change, reuse existing runner
        }

        loadedContextClasses = dynamicContextClasses;
        lastStaticFilesHash = contextHash;

        // Check cache for system prompt
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

        executor = new ClaudeExecutor(runner, responseParser);
    }

    /**
     * Builds the full system prompt from source and context files.
     */
    private String buildSystemPrompt(List<SourceFile> sourceFiles, List<SourceFile> contextFiles) {
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
            appendContextFiles(sb, contextFiles);
        }

        return sb.toString();
    }

    /**
     * Appends context files with group headers for subdirectories.
     */
    private void appendContextFiles(StringBuilder sb, List<SourceFile> files) {
        Path contextRoot = contextFileCollector.getContextRoot();
        Map<String, List<SourceFile>> grouped = new java.util.LinkedHashMap<>();

        for (SourceFile f : files) {
            Path relative = contextRoot.relativize(f.path());
            String groupName = (relative.getNameCount() > 1) ? relative.getName(0).toString() : null;
            grouped.computeIfAbsent(groupName, k -> new ArrayList<>()).add(f);
        }

        for (Map.Entry<String, List<SourceFile>> entry : grouped.entrySet()) {
            String groupName = entry.getKey();
            if (groupName != null) sb.append("\n=== [").append(groupName).append("] ===\n");
            for (SourceFile f : entry.getValue()) {
                sb.append("\n--- ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }
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
        promptCache.clear();
        lastStaticFilesHash = 0;
    }
}
