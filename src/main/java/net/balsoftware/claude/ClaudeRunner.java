package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class ClaudeRunner {

    private final SourceFileCollector sourceFileCollector;
    private final ContextFileCollector contextFileCollector;
    private final ConversationStore conversationStore;
    private final ClaudeResponseParser responseParser;
    private final ClaudeClientFactory clientFactory;

    private ClaudeClient client;
    private ClaudeClientConfig currentConfig;

    private String cachedSystemPrompt = "";
    private int contextHash = 0;

    private List<SourceFile> lastSourceFiles = List.of();
    private List<SourceFile> lastContextFiles = List.of();

    // ~~~~~~~~~~~~~~~~ Constructors ~~~~~~~~~~~~~~~~

    public ClaudeRunner(
            ClaudeClientFactory clientFactory,
            SourceFileCollector sourceFileCollector,
            ContextFileCollector contextFileCollector,
            ConversationStore conversationStore,
            ClaudeResponseParser responseParser,
            ClaudeClientConfig initialConfig,
            List<Class<?>> initialContext
    ) {
        this.clientFactory = clientFactory;
        this.sourceFileCollector = sourceFileCollector;
        this.contextFileCollector = contextFileCollector;
        this.conversationStore = conversationStore;
        this.responseParser = responseParser;
        this.currentConfig = initialConfig;

        try {
            setContext(initialContext);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize context", e);
        }
    }

    // ~~~~~~~~~~~~~~~~ Context ~~~~~~~~~~~~~~~~

    public void setContext(List<Class<?>> contextClasses) throws IOException {
        List<SourceFile> contextFiles = contextFileCollector.collect();
        List<SourceFile> sourceFiles = sourceFileCollector.collect(contextClasses);

        int hash = contextClasses.hashCode() ^ contextFiles.hashCode();

        if (hash == contextHash && !cachedSystemPrompt.isBlank()) return;
        contextHash = hash;

        lastSourceFiles = sourceFiles;
        lastContextFiles = contextFiles;

        String newPrompt = buildFullSystemPrompt(sourceFiles, contextFiles);

        if (!newPrompt.equals(cachedSystemPrompt)) {
            cachedSystemPrompt = newPrompt;
            currentConfig = currentConfig.withSystemPrompt(cachedSystemPrompt);
            this.client = clientFactory.createClient(currentConfig);
        }
    }

    private String buildFullSystemPrompt(List<SourceFile> sourceFiles, List<SourceFile> contextFiles) {
        StringBuilder sb = new StringBuilder(ClaudeSystemPrompt.build());

        if (!sourceFiles.isEmpty()) {
            sb.append("\n\nSource files:\n");
            for (SourceFile f : sourceFiles) {
                sb.append("\n--- FILE: ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }

        if (!contextFiles.isEmpty()) {
            sb.append("\n\nAdditional context files:\n");
            sb.append(buildContextSection(contextFiles));
        }

        return sb.toString();
    }

    private String buildContextSection(List<SourceFile> files) {
        Path contextRoot = contextFileCollector.getContextRoot();
        Map<String, List<SourceFile>> grouped = new LinkedHashMap<>();

        for (SourceFile f : files) {
            Path relative = contextRoot.relativize(f.path());
            String groupName = (relative.getNameCount() > 1) ? relative.getName(0).toString() : null;
            grouped.computeIfAbsent(groupName, k -> new ArrayList<>()).add(f);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<SourceFile>> entry : grouped.entrySet()) {
            String groupName = entry.getKey();
            if (groupName != null) sb.append("\n=== [").append(groupName).append("] ===\n");
            for (SourceFile f : entry.getValue()) {
                sb.append("\n--- FILE: ").append(f.path()).append(" ---\n");
                sb.append(f.content()).append("\n");
            }
        }
        return sb.toString();
    }

    // ~~~~~~~~~~~~~~~~ Running ~~~~~~~~~~~~~~~~

    /**
     * Executes a user message and returns structured response with tokens.
     * Does NOT modify conversation state — that's done in ClaudeSession.
     */
    public ClaudeStructuredResponseWithTokens runStructured(String model, String userMessage) throws IOException {
        if (cachedSystemPrompt.isBlank())
            throw new IllegalStateException("Context/system prompt must be set before run(). Call setContext() first.");
        if (client == null)
            throw new IllegalStateException("Claude client is not initialized. Context must be set before run().");

        // Build messages from current turns + new user message
        List<ClaudeMessage> messages = conversationStore.toMessages();
        messages.add(new ClaudeMessage(ClaudeRole.USER, userMessage));

        OKHttpClaudeClient.RawResponse raw = client.send(model, messages);
        if (raw == null) {
            throw new IllegalStateException("Claude client returned null response. This indicates a misconfigured or mocked client.");
        }

        ClaudeStructuredResponse structured = responseParser.parseStructured(raw.text());

        return new ClaudeStructuredResponseWithTokens(
                structured,
                raw.inputTokens(),
                raw.outputTokens(),
                raw.cacheCreationTokens(),
                raw.cacheReadTokens()
        );
    }

    // ~~~~~~~~~~~~~~~~ Getters ~~~~~~~~~~~~~~~~

    ClaudeClient getClientForTest() { return client; }
    String getCachedSystemPrompt() { return cachedSystemPrompt; }
    ClaudeClientConfig getClientConfigForTest() { return currentConfig; }
    public List<SourceFile> getLastSourceFiles() { return lastSourceFiles; }
    public List<SourceFile> getLastContextFiles() { return lastContextFiles; }
}
