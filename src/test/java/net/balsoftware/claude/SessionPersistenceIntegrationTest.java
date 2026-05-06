package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
}
