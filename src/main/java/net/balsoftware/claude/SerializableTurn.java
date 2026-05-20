package net.balsoftware.claude;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Serializable representation of a turn for persistence.
 * Persists the full structured response and per-turn token counts so a faithful
 * {@link ClaudeTurn} can be reconstructed on restore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializableTurn {
    private final String userMessage;
    private final String assistantMessage;
    private final ClaudeStructuredResponse structured;
    private final int inputTokens;
    private final int outputTokens;
    private final int cacheCreationTokens;
    private final int cacheReadTokens;

    @JsonCreator
    public SerializableTurn(
            @JsonProperty("userMessage") String userMessage,
            @JsonProperty("assistantMessage") String assistantMessage,
            @JsonProperty("structured") ClaudeStructuredResponse structured,
            @JsonProperty("inputTokens") int inputTokens,
            @JsonProperty("outputTokens") int outputTokens,
            @JsonProperty("cacheCreationTokens") int cacheCreationTokens,
            @JsonProperty("cacheReadTokens") int cacheReadTokens
    ) {
        this.userMessage = userMessage;
        this.assistantMessage = assistantMessage;
        this.structured = structured;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheCreationTokens = cacheCreationTokens;
        this.cacheReadTokens = cacheReadTokens;
    }

    /**
     * Backward-compatible convenience constructor for history-only turns
     * (no structured response or token data).
     */
    public SerializableTurn(String userMessage, String assistantMessage) {
        this(userMessage, assistantMessage, null, 0, 0, 0, 0);
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public ClaudeStructuredResponse getStructured() {
        return structured;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public int getCacheCreationTokens() {
        return cacheCreationTokens;
    }

    public int getCacheReadTokens() {
        return cacheReadTokens;
    }

    /**
     * Reconstructs a {@link ClaudeTurn} from this persisted turn.
     * If no structured response was persisted, a minimal explanation response is
     * synthesized from the assistant message so the turn can still be replayed as
     * conversation history.
     */
    public ClaudeTurn toClaudeTurn() {
        ClaudeStructuredResponse response = structured != null
                ? structured
                : new ClaudeStructuredResponse(
                        ClaudeStructuredResponse.Type.explanation,
                        assistantMessage != null ? assistantMessage : "",
                        List.of());

        return new ClaudeTurn(
                userMessage,
                assistantMessage,
                response,
                inputTokens,
                outputTokens,
                cacheCreationTokens,
                cacheReadTokens
        );
    }
}
