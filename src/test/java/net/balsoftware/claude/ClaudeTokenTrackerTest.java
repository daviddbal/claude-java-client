package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeTokenTrackerTest {

    private ClaudeTokenTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ClaudeTokenTracker();
    }

    private static ClaudeStructuredResponseWithTokens resp(int in, int out, int cacheWrite, int cacheRead) {
        return new ClaudeStructuredResponseWithTokens(
                new ClaudeStructuredResponse(ClaudeStructuredResponse.Type.explanation, "d", List.of()),
                in, out, cacheWrite, cacheRead
        );
    }

    @Test
    void accumulateSumsAllCounts() {
        tracker.accumulate(resp(10, 5, 0, 0));
        tracker.accumulate(resp(20, 7, 3, 0));

        assertEquals(30, tracker.getTotalInputTokens());
        assertEquals(12, tracker.getTotalOutputTokens());
        assertEquals(3, tracker.getTotalCacheCreationTokens());
        assertEquals(0, tracker.getTotalCacheReadTokens());
    }

    @Test
    void cacheReadMarksHitObserved() {
        assertFalse(tracker.isCacheHitObserved());

        tracker.accumulate(resp(10, 5, 0, 4));

        assertTrue(tracker.isCacheHitObserved());
        assertEquals(4, tracker.getTotalCacheReadTokens());
    }

    @Test
    void cacheStatusTransitions() {
        assertEquals("NO CACHE", tracker.getCacheStatus());

        tracker.accumulate(resp(10, 5, 8, 0)); // cache write only
        assertEquals("CACHE MISS", tracker.getCacheStatus());

        tracker.accumulate(resp(10, 5, 0, 2)); // a read appears
        assertEquals("CACHE HIT ✓", tracker.getCacheStatus());
    }

    @Test
    void restoreFromSnapshotReplacesState() {
        tracker.accumulate(resp(99, 99, 99, 99));

        tracker.restoreFromSnapshot(100, 50, 10, 0);
        assertEquals(100, tracker.getTotalInputTokens());
        assertEquals(50, tracker.getTotalOutputTokens());
        assertEquals(10, tracker.getTotalCacheCreationTokens());
        assertEquals(0, tracker.getTotalCacheReadTokens());
        assertFalse(tracker.isCacheHitObserved(), "no cache reads restored → no hit");

        tracker.restoreFromSnapshot(1, 1, 1, 5);
        assertTrue(tracker.isCacheHitObserved(), "restored cache reads → hit observed");
    }

    @Test
    void resetClearsEverything() {
        tracker.accumulate(resp(10, 5, 5, 5));

        tracker.reset();

        assertEquals(0, tracker.getTotalInputTokens());
        assertEquals(0, tracker.getTotalOutputTokens());
        assertEquals(0, tracker.getTotalCacheCreationTokens());
        assertEquals(0, tracker.getTotalCacheReadTokens());
        assertFalse(tracker.isCacheHitObserved());
        assertEquals("NO CACHE", tracker.getCacheStatus());
    }

    @Test
    void tokenSummaryReportsCounts() {
        tracker.accumulate(resp(10, 5, 0, 0));

        String summary = tracker.tokenSummary();
        assertTrue(summary.contains("in: 10"), summary);
        assertTrue(summary.contains("out: 5"), summary);
    }
}
