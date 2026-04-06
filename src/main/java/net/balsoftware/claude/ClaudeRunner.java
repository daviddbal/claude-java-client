package net.balsoftware.claude;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class ClaudeRunner {

    // Trimmed system prompt to reduce input tokens on every request
    private static final String BASE_SYSTEM_PROMPT =
            "You are a senior Java software engineer. Respond ONLY with valid JSON:\n" +
                    "{\"description\":\"<summary>\",\"files\":[{\"path\":\"<path>\",\"content\":\"<full file content>\"}]}\n" +
                    "No markdown, no extra text outside the JSON.";

    private final ClaudeClient claudeClient;
    private final SourceFileCollector sourceFileCollector;
    private final ConversationStore conversationStore;
    private final ClaudeResponseParser responseParser;

    // Cache context hash to avoid re-sending unchanged source files
    private int contextHash = 0;

    public ClaudeRunner(
            ClaudeClient claudeClient,
            SourceFileCollector sourceFileCollector,
            ConversationStore conversationStore,
            ClaudeResponseParser responseParser
    ) {
        this.claudeClient        = claudeClient;
        this.sourceFileCollector = sourceFileCollector;
        this.conversationStore   = conversationStore;
        this.responseParser      = responseParser;
    }

    /**
     * Sets the source-file context. Skips rebuilding the system prompt if the
     * set of context classes has not changed since the last call.
     */
    public void setContext(List<Class<?>> contextClasses) throws IOException {
        int hash = contextClasses.hashCode();
        if (hash == contextHash) return;   // unchanged — skip disk reads and prompt rebuild
        contextHash = hash;

        List<SourceFile> sourceFiles = sourceFileCollector.collect(contextClasses);
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);

        if (!sourceFiles.isEmpty()) {
            sb.append("\nYou have the following source files as context:\n");
            for (SourceFile f : sourceFiles) {
                sb.append("\n--- FILE: ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }

        conversationStore.setSystemPrompt(sb.toString());
    }

    /**
     * Send a plain user message. Context should have been set via
     * {@link #setContext} beforehand; it is NOT re-attached here.
     */
    public ClaudeResponse run(String model, String userMessage) throws IOException {
        if (conversationStore.getSystemPrompt().isBlank()) {
            conversationStore.setSystemPrompt(BASE_SYSTEM_PROMPT);
        }

        conversationStore.addUserMessage(userMessage);

        ClaudeClient.RawResponse raw = claudeClient.send(model, conversationStore.getMessages());
        ClaudeResponse response = responseParser.parse(raw.text(), raw.inputTokens(), raw.outputTokens());

        // Store compact summary — NOT the full JSON — to keep history small
        conversationStore.addAssistantMessage(buildHistorySummary(response));

        return response;
    }

    // ------------------------------------------------------------------ private

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