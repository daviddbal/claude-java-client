package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionPersistenceIntegrationTest {

    private ClaudeSession session;
    private SessionPersistence persistence;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;

        ClaudeClientFactory mockFactory = config -> new OKHttpClaudeClient(
                config.apiKey(),
                config.maxTokens(),
                config.systemPrompt()
        ) {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {
                return new RawResponse(
                        "{\"type\":\"explanation\",\"description\":\"mock response\",\"files\":[]}",
                        10, 5, 0, 0
                );
            }
        };

        session = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(mockFactory)
                .build();

        // Ensure session is pristine before each test
        session.resetAll();

        persistence = new SessionPersistence(tempDir);
    }

    @Test
    void testSaveAndLoadSessionState() throws IOException {
        session.loadContext(List.of(String.class));
        session.ask("What is Java?");
        session.ask("Tell me more");

        persistence.saveSession("test-session", session);

        SessionSnapshot loaded = persistence.loadSession("test-session");

        assertEquals(2, loaded.getTurns().size(),
                "Should have 2 turns: one for 'What is Java?' and one for 'Tell me more'");

        assertIterableEquals(
                List.of("What is Java?", "Tell me more"),
                loaded.getTurns().stream().map(SerializableTurn::getUserMessage).toList()
        );

        assertTrue(loaded.getTotalInputTokens() > 0,
                "Should have tracked input tokens");
    }

    @Test
    void testGetConversationHistory() throws IOException {
        session.loadContext(List.of());
        session.ask("First question");
        session.ask("Second question");

        List<String> history = session.getConversationHistory();

        // Should have 2 user messages (one per turn)
        assertEquals(2, history.size(),
                "Conversation history should contain user messages from each turn");
        assertTrue(history.contains("First question"));
        assertTrue(history.contains("Second question"));
    }

    @Test
    void testGetLastResponse() throws IOException {
        session.loadContext(List.of());
        ClaudeStructuredResponseWithTokens response = session.ask("Test");

        ClaudeStructuredResponseWithTokens lastResponse = session.getLastResponse();
        assertNotNull(lastResponse,
                "Last response should be cached");
        assertEquals(response.structured().description(), lastResponse.structured().description(),
                "Last response should match the most recent ask() call");
    }

    @Test
    void testAppPoemConversationPersistence(@TempDir Path tempDir) throws IOException {
        String apiKey = "DUMMY KEY";
        SessionPersistence persistence = new SessionPersistence(tempDir);

        ClaudeClientFactory appMock = config -> new OKHttpClaudeClient(apiKey, config.maxTokens(), "") {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {
                String lastPrompt = conversation.get(conversation.size() - 1).content();

                if (lastPrompt.contains("write me a short poem about frogs and the sun")) {
                    return new RawResponse("""
                    {"type":"explanation","description":"Three haikus about frogs and the sun","files":[{"path":"frogs_and_sun.txt","content":"Morning sun warms the pond\\nA frog leaps through golden light\\nCroaks greet the new day"}]}
                    """, 116, 152, 0, 0);
                }

                if (lastPrompt.contains("give me the poem in desription, not a file")) {
                    return new RawResponse("""
                    {"type":"explanation","description":"Green frog on the lily pad,\\nBasking in warm sunlight,\\nLeaps toward golden rays.\\n\\nSun climbs the morning sky,\\nFrog's croak echoes through the marsh,\\nDay has just begun.\\n\\nWarm pond, frog at rest,\\nSun sinks below the treeline,\\nCroaking fades to night.","files":[]}
                    """, 155, 114, 0, 0);
                }

                return new RawResponse("""
                {"type":"explanation","description":"App-like response","files":[]}
                """, 100, 50, 0, 0);
            }
        };

        ClaudeSession session = ClaudeSession.builder()
                .apiKey(apiKey)
                .clientFactory(appMock)
                .model(ClaudeModel.HAIKU_5.id())
                .build();

        session.loadContext(List.of());

        session.ask("write me a short poem about frogs and the sun. haiku style");
        session.ask("give me the poem in desription, not a file");

        assertEquals(2, session.getTurnCount());
        assertTrue(session.getTotalInputTokens() > 0);
        assertTrue(session.getTotalOutputTokens() > 0);

        persistence.saveSession("app-poem-session", session);
        SessionSnapshot snapshot = persistence.loadSession("app-poem-session");

        assertEquals(2, snapshot.turns().size());
        assertEquals(
                List.of(
                        "write me a short poem about frogs and the sun. haiku style",
                        "give me the poem in desription, not a file"
                ),
                snapshot.turns().stream().map(SerializableTurn::getUserMessage).toList()
        );

        assertEquals(session.getTotalInputTokens(), snapshot.totalInputTokens());
        assertEquals(session.getTotalOutputTokens(), snapshot.totalOutputTokens());

        Path manifestPath = tempDir.resolve("app-poem-session").resolve("manifest.json");
        assertTrue(Files.exists(manifestPath), "manifest.json should exist for inspection");
        System.out.println("Inspect saved session here: " + manifestPath.toAbsolutePath());
    }
}
