package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClaudeRunner {

    private static final String BASE_SYSTEM_PROMPT =
            "You are a senior Java software engineer assistant. " +
                    "When asked to generate or modify code, respond ONLY with valid JSON:\n" +
                    "{\"description\":\"<summary>\",\"files\":[{\"path\":\"<path>\",\"content\":\"<full file content>\"}]}\n" +
                    "For all other questions (explanations, discussions, analysis), respond in plain text.";

    private final ClaudeClient claudeClient;
    private final SourceFileCollector sourceFileCollector;
    private final ContextFileCollector contextFileCollector;
    private final ConversationStore conversationStore;
    private final ClaudeResponseParser responseParser;

    private int contextHash = 0;

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

    public void setContext(List<Class<?>> contextClasses) throws IOException {
        List<SourceFile> contextDirFiles = contextFileCollector.collect();

        int hash = contextClasses.hashCode() ^ contextDirFiles.hashCode();
        if (hash == contextHash) return;
        contextHash = hash;

        List<SourceFile> sourceFiles = sourceFileCollector.collect(contextClasses);

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

        conversationStore.setSystemPrompt(sb.toString());
    }

    public ClaudeResponse run(String model, String userMessage) throws IOException {
        if (conversationStore.getSystemPrompt().isBlank()) {
            conversationStore.setSystemPrompt(BASE_SYSTEM_PROMPT);
        }

        conversationStore.addUserMessage(userMessage);

        ClaudeClient.RawResponse raw = claudeClient.send(model, conversationStore.getMessages());
        ClaudeResponse response = responseParser.parse(
                raw.text(),
                raw.inputTokens(),
                raw.outputTokens(),
                raw.cacheCreationTokens(),
                raw.cacheReadTokens()
        );

        conversationStore.addAssistantMessage(buildHistorySummary(response));

        return response;
    }

    // ------------------------------------------------------------------ private

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