package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionCacheTest {

    private ClaudeSession session;

    @BeforeEach
    void setUp() {
        session = ClaudeSession.builder()
                .apiKey("fake-api-key-for-test")
                .model("claude-instant-1")
                .build();
    }

    @Test
    void testStaticContextCaching() throws IOException {
        // Simulate loading a large static context
        List<Class<?>> context = List.of(ClaudeClientFactory.class);

        session.loadContext(context);

        // First ask - should be a cache MISS
        ClaudeStructuredResponseWithTokens firstResponse = session.ask("Change message to Hello Alice");
        String firstTokenSummary = session.tokenSummary();

        // Assert cache was not hit
        assertFalse(session.isCacheHitObserved(), "First call should not hit cache");
        assertEquals("CACHE MISS", session.getCacheStatus());

        // Second ask with same context and same message
        ClaudeStructuredResponseWithTokens secondResponse = session.ask("Change message to Hello Alice");
        String secondTokenSummary = session.tokenSummary();

        // Now cache should be hit
        assertTrue(session.isCacheHitObserved(), "Second call should hit cache");
        assertEquals("CACHE HIT ✓", session.getCacheStatus());

        // The responses should be identical
        assertEquals(firstResponse.structured().content(), secondResponse.structured().content());

        // Print summaries for debug
        System.out.println("First:  " + firstTokenSummary);
        System.out.println("Second: " + secondTokenSummary);
    }

    @Test
    void testPromptCaching() throws IOException {
        ClaudeSession session = new ClaudeSession.Builder()
                .apiKey("DUMMY_KEY")
                .maxTokens(1000)
                .clientFactory(cfg -> new MockClaudeClient(cfg.systemPrompt()))
                .build();

        List<Class<?>> context = List.of(ClaudeClientFactory.class);

        // first call builds prompt and caches it
        ClaudeStructuredResponseWithTokens resp1 = session.ask("first message", context);
        assertFalse(session.isCacheHitObserved(), "First call should NOT hit cache");

        // second call should hit cache
        ClaudeStructuredResponseWithTokens resp2 = session.ask("second message", context);
        assertTrue(session.isCacheHitObserved(), "Second call SHOULD hit cache");

        System.out.println("First call cache write: " + resp1.cacheCreationTokens() +
                ", cache read: " + resp1.cacheReadTokens());
        System.out.println("Second call cache write: " + resp2.cacheCreationTokens() +
                ", cache read: " + resp2.cacheReadTokens());
    }

    public static String buildCacheTestPrompt() {
        String chunk = "A".repeat(1024);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(chunk);
        }
        return sb.toString();
    }

    @Disabled
    @Test
    void shouldUsePromptCacheReliably() throws Exception {

        String apiKey = EnvConfig.getClaudeApiKey();
        assertNotNull(apiKey, "API key required for live test");

        ClaudeClient client = new ClaudeClient(
                apiKey,
                1024,
                buildCacheTestPrompt()
        );

        List<ClaudeMessage> messages = List.of(
                new ClaudeMessage(ClaudeRole.USER, "Say hello")
        );

        // ---------------- FIRST REQUEST ----------------
        String modelId = ClaudeModel.HAIKU_5.id();
        ClaudeClient.RawResponse first =
                client.send(modelId, messages);

        System.out.println("FIRST RESPONSE:");
        System.out.println(first);

        assertTrue(first.inputTokens() > 0, "First request should consume input tokens");

        // NOTE: we do NOT assert cache creation here because it is not deterministic

        // ---------------- SECOND REQUEST (retry loop for cache hit) ----------------
        ClaudeClient.RawResponse second = null;
        boolean cacheHit = false;

        for (int i = 0; i < 2; i++) {

            Thread.sleep(500);

            second = client.send(modelId, messages);

            System.out.println("ATTEMPT " + i + " SECOND RESPONSE:");
            System.out.println(second);

            if (second.cacheReadTokens() > 0) {
                cacheHit = true;
                break;
            }
        }

        assertNotNull(second, "Second response must not be null");

        assertTrue(cacheHit,
                "Expected cache read on second request, but none occurred");

        assertTrue(second.cacheReadTokens() > 0,
                "Cache read tokens should be > 0 on successful cache hit");

        System.out.println("CACHE SUCCESS ✓");
    }
}