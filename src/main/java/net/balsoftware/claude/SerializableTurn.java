package net.balsoftware.claude;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Serializable representation of a turn for persistence.
 */
public class SerializableTurn {
    private final String userMessage;
    private final String assistantMessage;

    @JsonCreator
    public SerializableTurn(
            @JsonProperty("userMessage") String userMessage,
            @JsonProperty("assistantMessage") String assistantMessage
    ) {
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    /**
     * Converts to ClaudeTurn (requires structured response construction).
     */
    public ClaudeTurn toClaudeTurn(
            ClaudeStructuredResponse structured,
            int inputTokens,
            int outputTokens,
            int cacheCreationTokens,
            int cacheReadTokens
    ) {
        return new ClaudeTurn(
                userMessage,
                assistantMessage,
                structured,
                inputTokens,
                outputTokens,
                cacheCreationTokens,
                cacheReadTokens
        );
    }
}
