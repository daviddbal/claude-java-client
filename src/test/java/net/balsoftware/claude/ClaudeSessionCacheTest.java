package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionCacheTest {

    ClaudeSession session;

    @BeforeEach
    void setUp() {
        session = ClaudeSession.builder()
                .apiKey("irrelevant")
                .model("mock")
                .clientFactory(cfg -> new MockClaudeClient())
                .sourceFileCollector(new DummySourceFileCollector()) // This disables all file lookup!
                .build();
    }

    @Test
    void cacheHitWhenRequestStateIsIdentical() throws IOException {
        session.loadContext(List.of(String.class));

        String longPrompt = "hello world ".repeat(500); // ~6000 chars → enough tokens

        // First call: cold cache, no hit.
        session.ask(longPrompt);
        assertFalse(session.isCacheHitObserved(), "Cold call should not be a cache hit");

        // Reset the conversation so the request state (system prompt + empty history + message)
        // matches the first call exactly, then repeat → response cache hit.
        session.resetConversation();
        session.ask(longPrompt);
        assertTrue(session.isCacheHitObserved(),
                "Repeating an identical request (same state) should hit the response cache");
    }

    @Test
    void sameMessageInDifferentHistoryDoesNotReturnStaleAnswer() throws IOException {
        session.loadContext(List.of(String.class));

        String longPrompt = "explain ".repeat(500);

        // First ask adds a turn, so the second ask happens in a different conversation state.
        session.ask(longPrompt);
        session.ask(longPrompt);

        // The cache key includes conversation history, so the second identical message must
        // re-query rather than serve the earlier answer.
        assertFalse(session.isCacheHitObserved(),
                "Same message in a changed conversation must not reuse the earlier cached answer");
    }

    @Test
    void cacheIsContextAndPromptSensitive() throws IOException {
        List<Class<?>> ctx1 = List.of(MockClaudeClient.class);
        List<Class<?>> ctx2 = List.of(String.class);

        String longPrompt = "foo ".repeat(2000);

        // First context: cold, then a state-identical repeat hits.
        session.loadContext(ctx1);
        session.ask(longPrompt);
        assertFalse(session.isCacheHitObserved(), "First call in new context should NOT hit cache");

        session.resetConversation();
        session.ask(longPrompt);
        assertTrue(session.isCacheHitObserved(), "Repeated identical request should hit response cache");

        session.resetAll();

        // Different context rebuilds the system prompt and clears the cache.
        session.loadContext(ctx2);
        session.ask(longPrompt);
        assertFalse(session.isCacheHitObserved(), "Different context resets cache, should NOT hit");

        session.resetConversation();
        session.ask(longPrompt);
        assertTrue(session.isCacheHitObserved(), "Repeated identical request should hit cache in new context");
    }

    /**
     * This is the ONLY real API test.
     * Keep it disabled and run manually.
     */
    @Disabled("Integration test - requires real Claude API key")
    @Test
    void shouldUsePromptCacheReliably() throws Exception {

        String apiKey = EnvConfig.getClaudeApiKey();
        assertNotNull(apiKey, "API key required for live test");

        OKHttpClaudeClient client = new OKHttpClaudeClient(
                apiKey,
                1024,
                buildCacheTestPrompt()
        );

        List<ClaudeMessage> messages = List.of(
                new ClaudeMessage(ClaudeRole.USER, "Say hello")
        );

        OKHttpClaudeClient.RawResponse first =
                client.send(ClaudeModel.HAIKU_5.id(), messages);

        assertTrue(first.inputTokens() > 0);

        OKHttpClaudeClient.RawResponse second = null;
        boolean cacheHit = false;

        for (int i = 0; i < 2; i++) {

            Thread.sleep(500);

            second = client.send(ClaudeModel.HAIKU_5.id(), messages);

            if (second.cacheReadTokens() > 0) {
                cacheHit = true;
                break;
            }
        }

        assertNotNull(second);

        assertTrue(cacheHit, "Expected cache read on second request");

        assertTrue(second.cacheReadTokens() > 0);

        System.out.println("CACHE SUCCESS ✓");
    }

    public static String buildCacheTestPrompt() {
        String chunk = "A".repeat(900);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(chunk);
        }
        return sb.toString();
    }
}
