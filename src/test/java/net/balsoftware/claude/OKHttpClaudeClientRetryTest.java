package net.balsoftware.claude;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real OKHttpClaudeClient.send() retry/backoff behavior against an
 * in-process MockWebServer.
 */
class OKHttpClaudeClientRetryTest {

    private static final String SUCCESS_BODY =
            "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2,"
            + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}";

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /** Real client pointed at the mock server. */
    private OKHttpClaudeClient clientFor(MockWebServer server) {
        return new OKHttpClaudeClient("test-key", 1024, "system prompt") {
            @Override
            String apiUrl() {
                return server.url("/v1/messages").toString();
            }
        };
    }

    private OKHttpClaudeClient.RawResponse sendOnce() throws IOException {
        return clientFor(server).send("model", List.of(new ClaudeMessage(ClaudeRole.USER, "hi")));
    }

    private static MockResponse transientError(int code) {
        // Retry-After: 0 keeps the test fast (no real backoff sleep).
        return new MockResponse().setResponseCode(code).setHeader("Retry-After", "0").setBody("transient");
    }

    @Test
    void succeedsWithoutRetryOnSuccess() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(SUCCESS_BODY));

        OKHttpClaudeClient.RawResponse resp = sendOnce();

        assertEquals("hello", resp.text());
        assertEquals(5, resp.inputTokens());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void retriesThenSucceedsOnTransientError() throws IOException {
        server.enqueue(transientError(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(SUCCESS_BODY));

        OKHttpClaudeClient.RawResponse resp = sendOnce();

        assertEquals("hello", resp.text());
        assertEquals(2, server.getRequestCount(), "should retry once then succeed");
    }

    @Test
    void honorsRetryAfterHeader() throws IOException {
        // Retry-After: 1s should drive the wait, distinctly longer than the ~500ms default backoff.
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("rate limited"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(SUCCESS_BODY));

        long start = System.nanoTime();
        sendOnce();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, server.getRequestCount());
        assertTrue(elapsedMs >= 900,
                "should wait ~1s per Retry-After rather than the default backoff; was " + elapsedMs + "ms");
    }

    @Test
    void failsFastOnNonRetryableError() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        IOException ex = assertThrows(IOException.class, this::sendOnce);

        assertTrue(ex.getMessage().contains("400"), ex.getMessage());
        assertEquals(1, server.getRequestCount(), "a non-retryable error must not be retried");
    }

    @Test
    void givesUpAfterMaxRetries() {
        // 1 initial attempt + 3 retries = 4 calls; enqueue more than enough.
        for (int i = 0; i < 5; i++) {
            server.enqueue(transientError(529));
        }

        IOException ex = assertThrows(IOException.class, this::sendOnce);

        assertTrue(ex.getMessage().contains("529"), ex.getMessage());
        assertEquals(4, server.getRequestCount(), "1 initial attempt + MAX_RETRIES (3)");
    }
}
