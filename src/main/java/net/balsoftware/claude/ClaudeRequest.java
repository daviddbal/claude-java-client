package net.balsoftware.claude;

import java.util.List;

public record ClaudeRequest(
        String model,
        String userMessage,
        List<Class<?>> contextClasses
) {}