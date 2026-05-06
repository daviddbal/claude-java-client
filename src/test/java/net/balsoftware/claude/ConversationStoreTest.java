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
    void getMessagesIsUnmodifiable() {
        ConversationStore store = new ConversationStore();
        store.addTurn(createDummyTurn("hi", "bye"));
        assertThrows(UnsupportedOperationException.class,
                () -> store.getMessages().add(new ClaudeMessage(ClaudeRole.USER, "extra")));
    }
}