package net.balsoftware.claude;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {

    // Helper to create a complete turn for testing
    private ClaudeTurn createDummyTurn(String user, String assistant) {
        return new ClaudeTurn(
                user,
                assistant,
                new ClaudeStructuredResponse(ClaudeStructuredResponse.Type.explanation, assistant, List.of()),
                1, 1, 0, 0
        );
    }

    @Test
    void systemPromptAppearsFirst() {
        ConversationStore store = new ConversationStore();
        store.setSystemPrompt("Be helpful");
        store.addTurn(createDummyTurn("Hello", "Hi"));

        List<ClaudeMessage> msgs = store.getMessages();
        assertEquals(ClaudeRole.SYSTEM, msgs.get(0).role());
        assertEquals("Be helpful", msgs.get(0).content());
    }

    @Test
    void clearTurnsPreservesSystemPrompt() {
        ConversationStore store = new ConversationStore();
        store.setSystemPrompt("system");
        store.addTurn(createDummyTurn("hello", "hi"));
        store.clearTurns();

        List<ClaudeMessage> msgs = store.getMessages();
        assertEquals(1, msgs.size());
        assertEquals(ClaudeRole.SYSTEM, msgs.get(0).role());
    }

    @Test
    void clearAllRemovesEverything() {
        ConversationStore store = new ConversationStore();
        store.setSystemPrompt("system");
        store.addTurn(createDummyTurn("hello", "hi"));
        store.clearAll();
        assertTrue(store.getMessages().isEmpty());
    }

    @Test
    void slidingWindowDropsOldestTurns() {
        ConversationStore store = new ConversationStore();
        // MAX_TURNS is 10. Add 11 turns to trigger the trim logic.
        for (int i = 0; i < 11; i++) {
            store.addTurn(createDummyTurn("user " + i, "assistant " + i));
        }

        // 11 turns added, 1 dropped = 10 turns remaining.
        assertEquals(10, store.getTurnCount());

        // Oldest turn (0) was dropped, so oldest remaining is "user 1".
        List<ClaudeMessage> msgs = store.getMessages();
        // No system prompt, so get(0) is the user message from Turn 1.
        assertEquals("user 1", msgs.get(0).content());
    }

    @Test
    void restoreTurnsReplacesExistingTurns() {
        ConversationStore store = new ConversationStore();
        store.addTurn(createDummyTurn("old", "stale"));

        store.restoreTurns(List.of(
                createDummyTurn("q1", "a1"),
                createDummyTurn("q2", "a2")
        ));

        assertEquals(2, store.getTurnCount());
        List<ClaudeMessage> msgs = store.getMessages();
        assertEquals("q1", msgs.get(0).content());
        assertEquals("a1", msgs.get(1).content());
        assertEquals("q2", msgs.get(2).content());
    }

    @Test
    void restoreTurnsHonorsSlidingWindow() {
        ConversationStore store = new ConversationStore();

        List<ClaudeTurn> twelve = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(createDummyTurn("user " + i, "assistant " + i));
        }

        store.restoreTurns(twelve);

        // MAX_TURNS is 10, so the two oldest are trimmed.
        assertEquals(10, store.getTurnCount());
        assertEquals("user 2", store.getMessages().get(0).content());
    }

    @Test
    void customMaxTurnsTrimsToConfiguredSize() {
        ConversationStore store = new ConversationStore(2);
        for (int i = 0; i < 5; i++) {
            store.addTurn(createDummyTurn("u" + i, "a" + i));
        }

        assertEquals(2, store.getTurnCount());
        assertEquals("u3", store.getMessages().get(0).content(), "only the last 2 turns are kept");
    }

    @Test
    void getMessagesIsUnmodifiable() {
        ConversationStore store = new ConversationStore();
        store.addTurn(createDummyTurn("hi", "bye"));
        assertThrows(UnsupportedOperationException.class,
                () -> store.getMessages().add(new ClaudeMessage(ClaudeRole.USER, "extra")));
    }
}