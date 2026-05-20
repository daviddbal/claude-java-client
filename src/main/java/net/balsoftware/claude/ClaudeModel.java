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

    /**
     * Default model used when none is specified.
     */
    public static ClaudeModel defaultModel() {
        return HAIKU_5;
    }
}