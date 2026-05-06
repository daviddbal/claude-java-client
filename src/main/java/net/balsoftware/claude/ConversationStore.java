package net.balsoftware.claude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages conversation state using turn-based model.
 * Each turn is atomic: one user message + one assistant response.
 */
public class ConversationStore {

    private static final int MAX_TURNS = 10;

    private String systemPrompt = "";
    private final List<ClaudeTurn> turns = new ArrayList<>();

    // ================================================================ system prompt

    public void setSystemPrompt(String content) {
        this.systemPrompt = content == null ? "" : content;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    // ================================================================ turn management

    /**
     * Adds a completed turn to the conversation.
     * This is the only way to add conversation state.
     */
    public void addTurn(ClaudeTurn turn) {
        turns.add(turn);
        trimIfNeeded();
    }

    /**
     * Returns all turns in order.
     */
    public List<ClaudeTurn> getTurns() {
        return Collections.unmodifiableList(new ArrayList<>(turns));
    }

    /**
     * Converts turns to message format for API calls.
     * This is the only place messages are constructed.
     */
    public List<ClaudeMessage> toMessages() {
        List<ClaudeMessage> messages = new ArrayList<>();
        for (ClaudeTurn turn : turns) {
            messages.add(new ClaudeMessage(ClaudeRole.USER, turn.getUserMessage()));
            messages.add(new ClaudeMessage(ClaudeRole.ASSISTANT, turn.getAssistantMessage()));
        }
        return messages;
    }

    /**
     * Returns all messages including system prompt (for backwards compatibility only).
     */
    public List<ClaudeMessage> getMessages() {
        List<ClaudeMessage> all = new ArrayList<>();
        if (!systemPrompt.isBlank()) {
            all.add(new ClaudeMessage(ClaudeRole.SYSTEM, systemPrompt));
        }
        all.addAll(toMessages());
        return Collections.unmodifiableList(all);
    }

    /**
     * Returns number of turns (not messages).
     */
    public int getTurnCount() {
        return turns.size();
    }

    // ================================================================ reset

    /**
     * Clears all turns but preserves system prompt.
     */
    public void clearTurns() {
        turns.clear();
    }

    /**
     * Clears everything: system prompt and all turns.
     */
    public void clearAll() {
        systemPrompt = "";
        turns.clear();
    }

    // ================================================================ private

    private void trimIfNeeded() {
        if (turns.size() > MAX_TURNS) {
            List<ClaudeTurn> trimmed = new ArrayList<>(
                    turns.subList(turns.size() - MAX_TURNS, turns.size())
            );
            turns.clear();
            turns.addAll(trimmed);
        }
    }
}
