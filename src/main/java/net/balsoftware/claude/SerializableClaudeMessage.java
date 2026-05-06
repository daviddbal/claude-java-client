package net.balsoftware.claude;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Serializable version of ClaudeMessage for persistence.
 */
public class SerializableClaudeMessage {
    private final String role;
    private final String content;

    @JsonCreator
    public SerializableClaudeMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content
    ) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    /**
     * Converts to ClaudeMessage.
     */
    public ClaudeMessage toClaudeMessage() {
        ClaudeRole roleEnum = ClaudeRole.valueOf(role.toUpperCase());
        return new ClaudeMessage(roleEnum, content);
    }
}
