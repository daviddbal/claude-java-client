package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {

    @Test
    void systemPromptAppearsFirst() {
        ConversationStore store = new ConversationStore();
        store.setSystemPrompt("Be helpful");
        store.addUserMessage("Hello");

        List<ClaudeMessage> msgs = store.getMessages();
        assertEquals(ClaudeRole.SYSTEM, msgs.get(0).role());
        assertEquals("Be helpful", msgs.get(0).content());
    }

    @Test
    void clearTurnsPreservesSystemPrompt() {
        ConversationStore store = new ConversationStore();
        store.setSystemPrompt("system");
        store.addUserMessage("hello");
        store.clearTurns();

        List<ClaudeMessage> msgs = store.getMessages();
        assertEquals(1, msgs.size());
        assertEquals(ClaudeRole.SYSTEM, msgs.get(0).role());
    }

    @Test
    void clearAllRemovesEverything() {
        ConversationStore store = new ConversationStore();
        store.setSystemPrompt("system");
        store.addUserMessage("hello");
        store.clearAll();
        assertTrue(store.getMessages().isEmpty());
    }

    @Test
    void slidingWindowDropsOldestTurns() {
        ConversationStore store = new ConversationStore();
        // Add 11 user+assistant pairs (> MAX_TURN_PAIRS=10)
        for (int i = 0; i < 11; i++) {
            store.addUserMessage("user " + i);
            store.addAssistantMessage("assistant " + i);
        }
        // Should have been trimmed to 10 pairs = 20 turn messages
        assertEquals(20, store.getTurnCount());
        // Oldest message should now be "user 1", not "user 0"
        List<ClaudeMessage> msgs = store.getMessages();
        ClaudeMessage first = msgs.get(0); // no system prompt set
        assertEquals("user 1", first.content());
    }

    @Test
    void getMessagesIsUnmodifiable() {
        ConversationStore store = new ConversationStore();
        store.addUserMessage("hi");
        assertThrows(UnsupportedOperationException.class,
                () -> store.getMessages().add(new ClaudeMessage(ClaudeRole.USER, "extra")));
    }
}