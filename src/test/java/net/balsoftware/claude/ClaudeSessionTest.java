package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionTest {

    private ClaudeSession session;

    @BeforeEach
    void setUp() {
        ClaudeClientFactory mockFactory = config -> new OKHttpClaudeClient(
                config.apiKey(),
                config.maxTokens(),
                config.systemPrompt()
        ) {
            private int callCount = 0;

            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {
                callCount++;
                return new RawResponse(
                        "{\"type\":\"explanation\",\"description\":\"response #" + callCount + "\" ,\"files\":[]}",
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
    }

    @Test
    void testGetConversationHistory() throws IOException {
        session.loadContext(List.of());
        session.ask("First question");
        session.ask("Second question");

        List<String> history = session.getConversationHistory();

        assertEquals(2, history.size(),
                "Conversation history should contain user messages from each turn");
        assertTrue(history.contains("First question"));
        assertTrue(history.contains("Second question"));
        assertEquals("First question", history.get(0));
        assertEquals("Second question", history.get(1));
    }

    @Test
    void testGetLastResponse() throws IOException {
        session.loadContext(List.of());
        ClaudeStructuredResponseWithTokens response1 = session.ask("Test 1");
        ClaudeStructuredResponseWithTokens response2 = session.ask("Test 2");

        ClaudeStructuredResponseWithTokens lastResponse = session.getLastResponse();
        assertNotNull(lastResponse, "Last response should be cached");
        assertEquals(response2, lastResponse, "Last response should match the most recent ask() call");
        assertEquals(response2.structured(), lastResponse.structured(),
                "Last response structured content should match most recent");
    }

    @Test
    void testGetTurnCount() throws IOException {
        session.loadContext(List.of());
        assertEquals(0, session.getTurnCount(), "Fresh session has zero turns");

        session.ask("First");
        assertEquals(1, session.getTurnCount(), "One ask creates one turn");

        session.ask("Second");
        assertEquals(2, session.getTurnCount(), "Second ask creates second turn");
    }

    @Test
    void testResetConversationClearsTurns() throws IOException {
        session.loadContext(List.of());
        session.ask("First");
        session.ask("Second");
        assertEquals(2, session.getTurnCount());

        session.resetConversation();
        assertEquals(0, session.getTurnCount(), "resetConversation should clear turns");
        assertEquals(0, session.getConversationHistory().size());
    }

    @Test
    void testResetAllClearsEverything() throws IOException {
        session.loadContext(List.of(String.class));
        session.ask("First");
        assertEquals(1, session.getTurnCount());
        assertTrue(session.getTotalInputTokens() > 0);

        session.resetAll();
        assertEquals(0, session.getTurnCount());
        assertEquals(0, session.getTotalInputTokens());
        assertEquals(0, session.getTotalOutputTokens());
        assertNull(session.getLastResponse());
    }

    @Test
    void testAskFailsWithoutLoadContext() {
        assertThrows(IllegalStateException.class, () -> session.ask("No context"),
                "ask() should fail without loadContext()");
    }

    @Test
    void testCacheHitBehavior() throws IOException {
        session.loadContext(List.of());

        ClaudeStructuredResponseWithTokens first = session.ask("Same question");
        assertFalse(session.isCacheHitObserved());

        // Asking again in a changed conversation (the first turn is now history) must NOT
        // serve the earlier answer — the request is genuinely different, so it re-queries.
        session.ask("Same question");
        assertFalse(session.isCacheHitObserved(),
                "Same message in a changed conversation should not be a stale cache hit");

        // Reset to the original state: the identical request now hits the cache and returns
        // the original response.
        session.resetConversation();
        ClaudeStructuredResponseWithTokens cached = session.ask("Same question");
        assertTrue(session.isCacheHitObserved(),
                "Identical request in identical state should hit the response cache");
        assertEquals(first.structured().description(), cached.structured().description(),
                "Cache hit should return the original response");
    }
}