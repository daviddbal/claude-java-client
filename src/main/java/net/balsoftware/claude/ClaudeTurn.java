// ClaudeTurn.java as a record
package net.balsoftware.claude;

import java.util.Objects;

public record ClaudeTurn(
        String userMessage,
        String assistantMessage,
        ClaudeStructuredResponse structured,
        int inputTokens,
        int outputTokens,
        int cacheCreationTokens,
        int cacheReadTokens
) {
    public ClaudeTurn {
        Objects.requireNonNull(userMessage, "userMessage cannot be null");
        Objects.requireNonNull(assistantMessage, "assistantMessage cannot be null");
        Objects.requireNonNull(structured, "structured response cannot be null");
    }

    public String getUserMessage() { return userMessage; }
    public String getAssistantMessage() { return assistantMessage; }
}