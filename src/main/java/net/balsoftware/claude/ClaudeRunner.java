package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runner for sending questions/messages to Claude using a static system prompt.
 *
 * The system prompt (built from context files/classes) is passed once to ClaudeClient,
 * which will then only send user/assistant messages and use prompt caching.
 */
public class ClaudeRunner {

    static final String BASE_SYSTEM_PROMPT =
            "You are a senior Java software engineer assistant. " +
                    "When asked to generate or modify code, respond ONLY with valid JSON:\n" +
                    "{\"description\":\"<summary>\",\"files\":[{\"path\":\"<path>\",\"content\":\"<full file content>\"}]}\n" +
                    "For all other questions (explanations, discussions, analysis), respond in plain text.";

    private final ClaudeClient claudeClient;
    private final SourceFileCollector sourceFileCollector;
    private final ContextFileCollector contextFileCollector;
    private final ConversationStore conversationStore;
    private final ClaudeResponseParser responseParser;

    // Static context that gets cached
    private String cachedSystemPrompt = "";
    private int contextHash = 0;

    // Debug visibility
    private List<SourceFile> lastSourceFiles = List.of();
    private List<SourceFile> lastContextFiles = List.of();

    public ClaudeRunner(
            ClaudeClient claudeClient,
            SourceFileCollector sourceFileCollector,
            ContextFileCollector contextFileCollector,
            ConversationStore conversationStore,
            ClaudeResponseParser responseParser
    ) {
        this.claudeClient         = claudeClient;
        this.sourceFileCollector  = sourceFileCollector;
        this.contextFileCollector = contextFileCollector;
        this.conversationStore    = conversationStore;
        this.responseParser       = responseParser;
    }

    /**
     * Load and cache context files and source files into a single static system prompt.
     * Only rebuilds if the input class set changes.
     */
    public void setContext(List<Class<?>> contextClasses) throws IOException {

        List<SourceFile> contextDirFiles = contextFileCollector.collect();
        List<SourceFile> sourceFiles = sourceFileCollector.collect(contextClasses);

        int hash = contextClasses.hashCode() ^ contextDirFiles.hashCode();
        if (hash == contextHash) {
            // Context unchanged, don't rebuild
            return;
        }
        contextHash = hash;

        // Store for debugging
        this.lastSourceFiles = sourceFiles;
        this.lastContextFiles = contextDirFiles;

        // Build the static system prompt (only done once per context)
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);

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

        // Set this system prompt only once, then pass to ClaudeClient
        this.cachedSystemPrompt = sb.toString();

        // Create a new client per context bump, or better: require restart if context changes.
        // But here, for flexibility, we require you to create a new ClaudeClient with the new prompt if desired.
        // (Don't mutate ClaudeClient's prompt after construction.)
        // If you want auto-recreation, add a claudeClient = new ClaudeClient(...)
        // But session framework ensures 1 system prompt per session.
    }

    /**
     * Run a request with cached system prompt and dynamic conversation turns.
     * This ensures the system prompt stays constant and gets cached by Claude.
     */
    public ClaudeResponse run(String model, String userMessage) throws IOException {
        if (cachedSystemPrompt.isBlank()) throw new IllegalStateException(
                "System prompt/context must be set by calling setContext() before run().");

        // Add user message to conversation history
        conversationStore.addUserMessage(userMessage);

        // Get only the conversation turns (no system prompt)
        List<ClaudeMessage> conversationTurns = conversationStore.getTurns();

        // Only send system prompt once! (handled by ClaudeClient)
        ClaudeClient.RawResponse raw =
                claudeClient.send(model, conversationTurns);

        ClaudeResponse response = responseParser.parse(
                raw.text(),
                raw.inputTokens(),
                raw.outputTokens(),
                raw.cacheCreationTokens(),
                raw.cacheReadTokens()
        );

        // Add assistant response to history (without touching the system prompt)
        conversationStore.addAssistantMessage(buildHistorySummary(response));

        return response;
    }

    // ------------------------------------------------------------------ debug accessors

    public List<SourceFile> getLastSourceFiles() {
        return lastSourceFiles;
    }

    public List<SourceFile> getLastContextFiles() {
        return lastContextFiles;
    }

    // ------------------------------------------------------------------ private helpers

    private String buildContextSection(List<SourceFile> files) {
        Path contextRoot = contextFileCollector.getContextRoot();
        Map<String, List<SourceFile>> grouped = new LinkedHashMap<>();

        for (SourceFile f : files) {
            Path relative = contextRoot.relativize(f.path());

            String groupName = (relative.getNameCount() > 1)
                    ? relative.getName(0).toString()
                    : null;

            grouped.computeIfAbsent(groupName, k -> new ArrayList<>()).add(f);
        }

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, List<SourceFile>> entry : grouped.entrySet()) {

            String groupName = entry.getKey();

            if (groupName != null) {
                sb.append("\n=== [").append(groupName).append("] ===\n");
            }

            for (SourceFile f : entry.getValue()) {
                sb.append("\n--- FILE: ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }

        return sb.toString();
    }

    private String buildHistorySummary(ClaudeResponse response) {
        if (response.files().isEmpty()) {
            return response.description();
        }

        String filePaths = response.files().stream()
                .map(GeneratedFile::path)
                .collect(Collectors.joining(", "));

        return response.description() + " [files: " + filePaths + "]";
    }
}