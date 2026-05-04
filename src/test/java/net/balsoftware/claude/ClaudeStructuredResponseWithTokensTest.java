package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeStructuredResponseWithTokensTest {

    private ClaudeSession session;

    @BeforeEach
    void setup() {
        // Mock factory simulates Claude client behavior
        ClaudeClientFactory mockFactory = config -> new OKHttpClaudeClient(
                config.apiKey(),
                config.maxTokens(),
                config.systemPrompt()
        ) {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {

                String lastMessage = conversation.get(conversation.size() - 1).content();

                String responseJson;
                int inputTokens = 10;
                int outputTokens = 5;
                int cacheWrite = 1;
                int cacheRead = 2;

                switch (lastMessage) {
                    case "code" -> responseJson = """
                        {
                          "type":"code",
                          "description":"code desc",
                          "files":[{"path":"A.txt","content":"System.out.println(\\"hi\\");"}]
                        }
                        """;
                    case "explanation" -> responseJson = """
                        {
                          "type":"explanation",
                          "description":"expl desc",
                          "files":[]
                        }
                        """;
                    case "code_with_explanation" -> responseJson = """
                        {
                          "type":"code_with_explanation",
                          "description":"code+exp desc",
                          "files":[{"path":"B.txt","content":"int x=42;"}]
                        }
                        """;
                    default -> responseJson = """
                        {
                          "type":"code",
                          "description":"default",
                          "files":[]
                        }
                        """;
                }

                return new RawResponse(responseJson, inputTokens, outputTokens, cacheWrite, cacheRead);
            }
        };

        session = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(mockFactory)
                .build();
    }

    @Test
    void testCodeResponseWithTokens() throws IOException {
        ClaudeStructuredResponseWithTokens response = session.ask("code", List.of());
        ClaudeStructuredResponse r = response.structured();

        assertEquals(ClaudeStructuredResponse.Type.code, r.type());
        assertEquals("code desc", r.description());
        assertEquals("System.out.println(\"hi\");", r.files().get(0).content());
        assertEquals("A.txt", r.files().get(0).path());

        assertEquals(10, response.inputTokens());
        assertEquals(5, response.outputTokens());
        assertEquals(1, response.cacheCreationTokens());
        assertEquals(2, response.cacheReadTokens());
    }

    @Test
    void testExplanationResponseWithTokens() throws IOException {
        ClaudeStructuredResponseWithTokens response = session.ask("explanation", List.of());
        ClaudeStructuredResponse r = response.structured();

        assertEquals(ClaudeStructuredResponse.Type.explanation, r.type());
        assertEquals("expl desc", r.description());
        assertTrue(r.files().isEmpty());

        assertEquals(10, response.inputTokens());
        assertEquals(5, response.outputTokens());
        assertEquals(1, response.cacheCreationTokens());
        assertEquals(2, response.cacheReadTokens());
    }

    @Test
    void testCodeWithExplanationResponseWithTokens() throws IOException {
        ClaudeStructuredResponseWithTokens response = session.ask("code_with_explanation", List.of());
        ClaudeStructuredResponse r = response.structured();

        assertEquals(ClaudeStructuredResponse.Type.code_with_explanation, r.type());
        assertEquals("code+exp desc", r.description());
        assertEquals("int x=42;", r.files().get(0).content());
        assertEquals("B.txt", r.files().get(0).path());

        assertEquals(10, response.inputTokens());
        assertEquals(5, response.outputTokens());
        assertEquals(1, response.cacheCreationTokens());
        assertEquals(2, response.cacheReadTokens());
    }

    @Test
    void testMultipleCallsAccumulateTokens() throws IOException {
        ClaudeStructuredResponseWithTokens first = session.ask("code", List.of());
        ClaudeStructuredResponseWithTokens second = session.ask("explanation", List.of());

        // Extract actual totals
        int totalIn = first.inputTokens() + second.inputTokens();
        int totalOut = first.outputTokens() + second.outputTokens();
        int totalCacheWrite = first.cacheCreationTokens() + second.cacheCreationTokens();
        int totalCacheRead = first.cacheReadTokens() + second.cacheReadTokens();

        assertEquals(20, totalIn);
        assertEquals(10, totalOut);
        assertEquals(2, totalCacheWrite);
        assertEquals(4, totalCacheRead);
    }

    @Test
    void testStructuredReturnsNonNull() throws IOException {
        ClaudeStructuredResponseWithTokens response = session.ask("code", List.of());
        assertNotNull(response.structured());
    }
}