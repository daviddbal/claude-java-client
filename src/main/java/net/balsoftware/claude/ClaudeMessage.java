package net.balsoftware.claude;

public record ClaudeMessage(
        ClaudeRole role,
        String content
) {}