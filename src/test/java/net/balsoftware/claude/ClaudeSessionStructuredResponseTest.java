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

        ClaudeClientFactory mockFactory = config -> new OKHttpClaudeClient(
                config.apiKey(),
                config.maxTokens(),
                config.systemPrompt()
        ) {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {

                String lastMessage = conversation.get(conversation.size() - 1).content();

                String responseJson;

                switch (lastMessage) {

                    case "please return code" -> responseJson = """
                        {
                          "type":"code",
                          "description":"my code desc",
                          "files":[
                            {"path":"A.txt","content":"System.out.println(\\"hi\\");"}
                          ]
                        }
                        """;

                    case "please return explanation" -> responseJson = """
                        {
                          "type":"explanation",
                          "description":"my explanation desc",
                          "files":[]
                        }
                        """;

                    case "please return code_with_explanation" -> responseJson = """
                        {
                          "type":"code_with_explanation",
                          "description":"code+exp desc",
                          "files":[
                            {"path":"B.txt","content":"int x=42;"}
                          ]
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
        ClaudeStructuredResponseWithTokens response =
                session.ask("please return code", List.of());

        ClaudeStructuredResponse r = response.structured();

        assertEquals(ClaudeStructuredResponse.Type.code, r.type());
        assertEquals("my code desc", r.description());

        assertEquals("System.out.println(\"hi\");",
                r.files().get(0).content());

        assertEquals("A.txt", r.files().get(0).path());
    }

    @Test
    void testExplanationResponse() throws IOException {
        ClaudeStructuredResponseWithTokens response =
                session.ask("please return explanation", List.of());

        ClaudeStructuredResponse r = response.structured();

        assertEquals(ClaudeStructuredResponse.Type.explanation, r.type());
        assertEquals("my explanation desc", r.description());

        assertTrue(r.files().isEmpty());
    }

    @Test
    void testCodeWithExplanationResponse() throws IOException {
        ClaudeStructuredResponseWithTokens response =
                session.ask("please return code_with_explanation", List.of());

        ClaudeStructuredResponse r = response.structured();

        assertEquals(ClaudeStructuredResponse.Type.code_with_explanation, r.type());
        assertEquals("code+exp desc", r.description());

        assertEquals("int x=42;",
                r.files().get(0).content());

        assertEquals("B.txt", r.files().get(0).path());
    }
}