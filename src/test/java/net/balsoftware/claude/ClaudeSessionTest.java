package net.balsoftware.claude;

import java.util.List;

public class ClaudeSessionTest {
    public static void main(String[] args) throws Exception {
        ClaudeClientFactory factory = config -> new OKHttpClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt());
        ClaudeSession session = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(factory)
                .build();

        // Load context (even empty)
        session.loadContext(List.of()); // ensures system prompt is set

        // Debug print
        System.out.println("BASE: '" + ClaudeSystemPrompt.build() + "'");
        System.out.println("Runner cached: '" + session.getRunner().getCachedSystemPrompt() + "'");

        // This cannot throw if BASE_SYSTEM_PROMPT is non-blank
        ClaudeStructuredResponseWithTokens response = session.ask("Hello, Claude!");

        System.out.println("Description: " + response.description());
        System.out.println("System prompt cached: " +
                ((session.getRunner() != null) && !session.getRunner().getCachedSystemPrompt().isBlank()));
        System.out.println("Token summary: " + session.tokenSummary());
    }
}