package net.balsoftware.claude;

public record ClaudeStructuredResponseWithTokens(
        ClaudeStructuredResponse structured,
        int inputTokens,
        int outputTokens,
        int cacheCreationTokens,
        int cacheReadTokens
) {
    public boolean hasFiles() {
        return structured != null && structured.hasFiles();
    }
    public ClaudeStructuredResponse.Type type() {
        return structured != null ? structured.type() : null;
    }
    public String description() {
        return structured != null ? structured.description() : null;
    }
    public String content() {
        return structured != null ? structured.content() : null;
    }
    public java.util.List<ClaudeStructuredResponse.FileItem> files() {
        return structured != null ? structured.files() : null;
    }
}