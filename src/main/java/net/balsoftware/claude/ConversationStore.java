package net.balsoftware.claude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConversationStore {

    private static final int MAX_TURN_PAIRS = 10;

    private String systemPrompt = "";
    private final List<ClaudeMessage> turns = new ArrayList<>();

    // ------------------------------------------------------------------ system prompt

    public void setSystemPrompt(String content) {
        this.systemPrompt = content == null ? "" : content;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    // ------------------------------------------------------------------ conversation turns

    public void addUserMessage(String content) {
        turns.add(new ClaudeMessage(ClaudeRole.USER, content));
        trimIfNeeded();
    }

    public void addAssistantMessage(String content) {
        turns.add(new ClaudeMessage(ClaudeRole.ASSISTANT, content));
        trimIfNeeded();
    }

    // ------------------------------------------------------------------ retrieval

    /**
     * Returns only the conversation turns (user/assistant messages).
     * Does NOT include the system prompt.
     * Use this when sending to Claude with a separate system prompt.
     */
    public List<ClaudeMessage> getTurns() {
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }

    /**
     * Returns all messages including the system prompt.
     * For backwards compatibility only — prefer getTurns() + separate system prompt.
     */
    public List<ClaudeMessage> getMessages() {
        List<ClaudeMessage> all = new ArrayList<>();
        if (!systemPrompt.isBlank()) {
            all.add(new ClaudeMessage(ClaudeRole.SYSTEM, systemPrompt));
        }
        all.addAll(turns);
        return Collections.unmodifiableList(all);
    }

    public int getTurnCount() {
        return turns.size();
    }

    // ------------------------------------------------------------------ reset

    public void clearTurns() {
        turns.clear();
    }

    public void clearAll() {
        systemPrompt = "";
        turns.clear();
    }

    // ------------------------------------------------------------------ private

    private void trimIfNeeded() {
        int maxTurnMessages = MAX_TURN_PAIRS * 2;
        if (turns.size() > maxTurnMessages) {
            List<ClaudeMessage> trimmed = new ArrayList<>(
                    turns.subList(turns.size() - maxTurnMessages, turns.size())
            );
            turns.clear();
            turns.addAll(trimmed);
        }
    }
}
