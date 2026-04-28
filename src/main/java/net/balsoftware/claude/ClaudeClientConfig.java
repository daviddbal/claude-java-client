package net.balsoftware.claude;

/**
 * Immutable configuration for ClaudeClient.
 */
public record ClaudeClientConfig(
        String apiKey,
        int maxTokens,
        boolean enableCaching,
        String systemPrompt
) {

    /**
     * Creates a new config with the given system prompt, preserving other fields.
     */
    public ClaudeClientConfig withSystemPrompt(String systemPrompt) {
        return new ClaudeClientConfig(this.apiKey, this.maxTokens, this.enableCaching, systemPrompt);
    }
}