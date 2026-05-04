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
    void cacheMissThenHitForIdenticalMessage() throws IOException {
        // Use a ubiquitous context class
        List<Class<?>> context = List.of(String.class);

        session.loadContext(context);

        // Use a long prompt to simulate enough tokens for caching
        String longPrompt = "hello world ".repeat(500); // ~6000 chars → enough tokens

        // First call: no cache hit (cold cache)
        ClaudeStructuredResponseWithTokens first = session.ask(longPrompt);
        // Note: MockClaudeClient always returns the same token pattern, so no actual Anthropic cache behavior
        // Instead, we test ClaudeSession's *response* cache

        // Second call: should hit *response* cache in ClaudeSession
        ClaudeStructuredResponseWithTokens second = session.ask(longPrompt);
        
        // The response cache key is based on (systemPrompt, message)
        // Since message is identical, it should hit the response cache
        assertTrue(session.isCacheHitObserved(), "Second call should observe cache behavior");
    }

    @Test
    void cacheIsContextAndPromptSensitive() throws IOException {
        List<Class<?>> ctx1 = List.of(MockClaudeClient.class);
        List<Class<?>> ctx2 = List.of(String.class);

        String longPrompt = "foo ".repeat(2000);

        // First context
        session.loadContext(ctx1);
        ClaudeStructuredResponseWithTokens resp1a = session.ask(longPrompt);
        assertFalse(session.isCacheHitObserved(), "First call in new context should NOT hit cache");
        
        ClaudeStructuredResponseWithTokens resp1b = session.ask(longPrompt);
        assertTrue(session.isCacheHitObserved(), "Second call with same message should hit response cache");

        session.resetAll();

        // Second (different) context
        session.loadContext(ctx2);
        ClaudeStructuredResponseWithTokens resp2a = session.ask(longPrompt);
        assertFalse(session.isCacheHitObserved(), "Different context resets cache, should NOT hit");
        
        ClaudeStructuredResponseWithTokens resp2b = session.ask(longPrompt);
        assertTrue(session.isCacheHitObserved(), "Should hit cache for repeated call in new context");
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
