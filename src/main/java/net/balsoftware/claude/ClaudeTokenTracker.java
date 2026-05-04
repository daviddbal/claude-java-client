package net.balsoftware.claude;

/**
 * Tracks token usage and caching statistics.
 * Responsibility: Accumulate and report token metrics.
 */
public class ClaudeTokenTracker {

    private int totalInputTokens = 0;
    private int totalOutputTokens = 0;
    private int totalCacheCreationTokens = 0;
    private int totalCacheReadTokens = 0;
    private boolean cacheHitObserved = false;

    /**
     * Accumulates tokens from a response.
     */
    public void accumulate(ClaudeStructuredResponseWithTokens response) {
        totalInputTokens += response.inputTokens();
        totalOutputTokens += response.outputTokens();
        totalCacheCreationTokens += response.cacheCreationTokens();

        if (response.cacheReadTokens() > 0) {
            totalCacheReadTokens += response.cacheReadTokens();
            cacheHitObserved = true;
        }
    }

    /**
     * Returns true if a cache hit has been observed in this session.
     */
    public boolean isCacheHitObserved() {
        return cacheHitObserved;
    }

    /**
     * Returns the cache status string.
     */
    public String getCacheStatus() {
        if (totalCacheReadTokens > 0) return "CACHE HIT ✓";
        if (totalCacheCreationTokens > 0) return "CACHE MISS";
        return "NO CACHE";
    }

    /**
     * Returns a human-readable token summary.
     */
    public String tokenSummary() {
        int totalIn = totalInputTokens + totalCacheCreationTokens + totalCacheReadTokens;

        String savings = totalIn > 0
                ? String.format("%.0f%%", (totalCacheReadTokens * 100.0) / totalIn)
                : "n/a";

        return String.format(
                "tokens in: %d out: %d | cache write: %d read: %d (saved ~%s) [%s]",
                totalInputTokens,
                totalOutputTokens,
                totalCacheCreationTokens,
                totalCacheReadTokens,
                savings,
                getCacheStatus()
        );
    }

    /**
     * Resets all tracking.
     */
    public void reset() {
        totalInputTokens = 0;
        totalOutputTokens = 0;
        totalCacheCreationTokens = 0;
        totalCacheReadTokens = 0;
        cacheHitObserved = false;
    }

    // -------- Getters --------

    public int getTotalInputTokens() {
        return totalInputTokens;
    }

    public int getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public int getTotalCacheCreationTokens() {
        return totalCacheCreationTokens;
    }

    public int getTotalCacheReadTokens() {
        return totalCacheReadTokens;
    }
}
