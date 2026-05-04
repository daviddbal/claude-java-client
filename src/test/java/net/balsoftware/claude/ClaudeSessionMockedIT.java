package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionMockedIT {

    // A test ClaudeClient that doesn't do HTTP, just records/invents responses
    public static class MockClaudeClient extends OKHttpClaudeClient {
        public MockClaudeClient(String apiKey, int maxTokens, String systemPrompt) {
            super(apiKey, maxTokens, systemPrompt);
        }

        @Override
        public RawResponse send(String model, List<ClaudeMessage> conversationTurns) {
            String json = """
                    {
                      "type": "explanation",
                      "description": "Hello, mock world!",
                      "content": "Hello, mock world!",
                      "files": []
                    }
                    """;

            return new RawResponse(
                    json,
                    42, 13,   // input/output tokens
                    0, 0      // cache creation/read tokens
            );
        }
    }

    @Test
    void testClaudeSessionIntegrationWithMock() throws Exception {
        // Use a factory that returns our mock
        ClaudeClientFactory factory = config -> new MockClaudeClient(
                config.apiKey(), config.maxTokens(), config.systemPrompt()
        );

        ClaudeSession session = ClaudeSession.builder()
                .apiKey("irrelevant-for-mock")
                .clientFactory(factory)
                .build();

        session.loadContext(List.of()); // Sets up system prompt/caching

        // Verify wiring and system prompt logic
        assertNotNull(session.getRunner().getCachedSystemPrompt());
        assertFalse(session.getRunner().getCachedSystemPrompt().isBlank());

        // Main integration call
        ClaudeStructuredResponseWithTokens response = session.ask("Hello, Claude!");

        // Assert the mock was invoked and result is deterministic
        assertEquals("Hello, mock world!", response.description());
        assertEquals(42, response.inputTokens());
        assertEquals(13, response.outputTokens());
        assertEquals(0, response.cacheCreationTokens());
        assertEquals(0, response.cacheReadTokens());

        // Ensure prompt and token summary reflect expected logic
        assertTrue(session.getRunner().getCachedSystemPrompt().contains("senior Java software engineer"));
        assertTrue(session.tokenSummary().contains("in: 42"));
        assertTrue(session.tokenSummary().contains("out: 13"));
    }
}