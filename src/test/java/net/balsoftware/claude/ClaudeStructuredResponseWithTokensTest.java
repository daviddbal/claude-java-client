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
        // Mock client factory
        ClaudeClientFactory mockFactory = config -> new ClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()) {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {
                String lastMessage = conversation.get(conversation.size() - 1).content();
                String responseJson;
                switch (lastMessage) {
                    case "code":
                        responseJson = """
                                {"type":"code","description":"code desc","content":"System.out.println(\\"hi\\");","explanation":null,"files":[{"path":"A.txt","content":"abc"}]}
                                """;
                        break;
                    case "explanation":
                        responseJson = """
                                {"type":"explanation","description":"expl desc","content":"This explains the logic","explanation":"This explains the logic","files":[]}
                                """;
                        break;
                    case "code_with_explanation":
                        responseJson = """
                                {"type":"code_with_explanation","description":"code+exp desc","content":"int x=42;","explanation":"Sets x","files":[{"path":"B.txt","content":"xyz"}]}
                                """;
                        break;
                    default:
                        responseJson = """
                                {"type":"code","description":"default","content":"// default code","explanation":null,"files":[]}
                                """;
                        break;
                }
                return new RawResponse(responseJson, 10, 5, 1, 2);
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
        assertEquals("System.out.println(\"hi\");", r.content());
        assertTrue(r.files().stream().anyMatch(f -> f.path().equals("A.txt")));

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
        assertEquals("This explains the logic", r.explanation());
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
        assertEquals("int x=42;", r.content());
        assertEquals("Sets x", r.explanation());
        assertTrue(r.files().stream().anyMatch(f -> f.path().equals("B.txt")));

        assertEquals(10, response.inputTokens());
        assertEquals(5, response.outputTokens());
        assertEquals(1, response.cacheCreationTokens());
        assertEquals(2, response.cacheReadTokens());
    }

    @Test
    void testMultipleCallsAccumulateTokens() throws IOException {
        session.ask("code", List.of());         // tokens: 10/5/1/2
        session.ask("explanation", List.of());  // tokens: 10/5/1/2

        String summary = session.tokenSummary();
        // Now the totals should be:
        // input: 10 + 10 = 20
        // output: 5 + 5 = 10
        // cache write: 1 + 1 = 2
        // cache read: 2 + 2 = 4

        assertTrue(summary.contains("in: 20"), "Input tokens not accumulated correctly");
        assertTrue(summary.contains("out: 10"), "Output tokens not accumulated correctly");
        assertTrue(summary.contains("cache write: 2"), "Cache creation tokens not accumulated correctly");
        assertTrue(summary.contains("cache read: 4"), "Cache read tokens not accumulated correctly");
    }

    @Test
    void testStructuredReturnsNonNull() throws IOException {
        ClaudeStructuredResponseWithTokens response = session.ask("code", List.of());
        assertNotNull(response.structured());
    }
}