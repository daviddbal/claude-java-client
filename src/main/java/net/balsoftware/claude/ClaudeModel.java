package net.balsoftware.claude;

public enum ClaudeModel {

    // NOTE: placeholders until Anthropic releases/you confirm exact IDs
    HAIKU_5("claude-haiku-4-5-20251001"),

    SONNET_4_6("claude-sonnet-4-6"),

    OPUS_4_7("claude-opus-4-7");

    private final String modelId;

    ClaudeModel(String modelId) {
        this.modelId = modelId;
    }

    public String id() {
        return modelId;
    }

    public static ClaudeModel fromEnv(String value) {
        if (value == null) return HAIKU_5;

        return switch (value.toLowerCase()) {
            case "haiku5", "haiku-5" -> HAIKU_5;
            case "sonnet4.6", "sonnet", "sonnet-4.6" -> SONNET_4_6;
            case "opus4.7", "opus", "opus-4.7" -> OPUS_4_7;
            default -> HAIKU_5;
        };
    }

    /**
     * Default model used when none is specified.
     */
    public static ClaudeModel defaultModel() {
        return HAIKU_5;
    }

    public static ClaudeModel cheapestModel() {
        return HAIKU_5;
    }
}