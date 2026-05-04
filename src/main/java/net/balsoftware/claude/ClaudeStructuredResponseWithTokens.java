package net.balsoftware.claude;

import java.util.List;

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

    public List<ClaudeStructuredResponse.FileItem> files() {
        return structured != null ? structured.files() : List.of();
    }

    // ---------------- CACHE DIAGNOSTICS ----------------

    public boolean isCacheHit() {
        return cacheReadTokens > 0;
    }

    public boolean isCacheMiss() {
        return cacheReadTokens == 0 && cacheCreationTokens > 0;
    }

    public String cacheStatus() {
        if (isCacheHit()) return "CACHE HIT ✓";
        if (isCacheMiss()) return "CACHE MISS";
        return "NO CACHE";
    }

    public String tokenSummary() {
        return String.format(
                "in: %d, out: %d | cache write: %d, cache read: %d | %s",
                inputTokens,
                outputTokens,
                cacheCreationTokens,
                cacheReadTokens,
                cacheStatus()
        );
    }
}