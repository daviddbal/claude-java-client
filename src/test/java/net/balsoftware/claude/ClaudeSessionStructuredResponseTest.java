package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionStructuredResponseTest {

    private ClaudeSession session;

    @BeforeEach
    void setup() {
        // Mock client factory returning deterministic JSON
        ClaudeClientFactory mockFactory = config -> new ClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()) {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {
                String lastMessage = conversation.get(conversation.size() - 1).content();
                String responseJson;
                switch (lastMessage) {
                    case "please return code":
                        responseJson = """
                            {"type":"code","description":"my code desc","content":"System.out.println(\\"hi\\");","explanation":null,"files":[{"path":"A.txt","content":"abc"}]}
                            """;
                        break;
                    case "please return explanation":
                        // FIX: content must be non-null for type 'explanation'
                        responseJson = """
                            {"type":"explanation","description":"my explanation desc","content":"This explains the logic","explanation":"This explains the logic","files":[]}
                            """;
                        break;
                    case "please return code_with_explanation":
                        responseJson = """
                            {"type":"code_with_explanation","description":"code+exp desc","content":"int x=42;","explanation":"This sets x to 42","files":[{"path":"B.txt","content":"xyz"}]}
                            """;
                        break;
                    default:
                        responseJson = """
                            {"type":"code","description":"default","content":"// default code","explanation":null,"files":[]}
                            """;
                        break;
                }
                return new RawResponse(responseJson, 10, 5, 0, 0);
            }
        };

        session = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(mockFactory)
                .build();
    }

    @Test
    void testCodeResponse() throws IOException {
        ClaudeStructuredResponseWithTokens response = session.ask("please return code", List.of());
        ClaudeStructuredResponse r = response.structured();
        assertEquals(ClaudeStructuredResponse.Type.code, r.type());
        assertEquals("my code desc", r.description());
        assertEquals("System.out.println(\"hi\");", r.content());
        assertTrue(r.files().stream().anyMatch(f -> f.path().equals("A.txt")));
    }

    @Test
    void testExplanationResponse() throws IOException {
        // No need for Mockito; the stub is already in the factory
        ClaudeStructuredResponseWithTokens response = session.ask("please return explanation", List.of());
        ClaudeStructuredResponse r = response.structured();

        assertEquals(ClaudeStructuredResponse.Type.explanation, r.type());
        assertEquals("my explanation desc", r.description());
        assertEquals("This explains the logic", r.explanation());
        assertTrue(r.files().isEmpty());
    }

    @Test
    void testCodeWithExplanationResponse() throws IOException {
        ClaudeStructuredResponseWithTokens response = session.ask("please return code_with_explanation", List.of());
        ClaudeStructuredResponse r = response.structured();
        assertEquals(ClaudeStructuredResponse.Type.code_with_explanation, r.type());
        assertEquals("code+exp desc", r.description());
        assertEquals("int x=42;", r.content());
        assertEquals("This sets x to 42", r.explanation());
        assertTrue(r.files().stream().anyMatch(f -> f.path().equals("B.txt")));
    }
}