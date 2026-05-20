package net.balsoftware.claude;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real SSE streaming path of OKHttpClaudeClient against a MockWebServer. */
class OKHttpClaudeClientStreamingTest {

    private static final String SSE = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":12,"cache_creation_input_tokens":0,"cache_read_input_tokens":3,"output_tokens":1}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":", world"}}

            event: message_delta
            data: {"type":"message_delta","delta":{},"usage":{"output_tokens":7}}

            event: message_stop
            data: {"type":"message_stop"}
            """;

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

    private OKHttpClaudeClient clientFor(MockWebServer server) {
        return new OKHttpClaudeClient("test-key", 1024, "system prompt") {
            @Override
            String apiUrl() {
                return server.url("/v1/messages").toString();
            }
        };
    }

    private static MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    @Test
    void streamsTextDeltasAndAccumulatesUsage() throws IOException, InterruptedException {
        server.enqueue(sse(SSE));

        List<String> deltas = new ArrayList<>();
        OKHttpClaudeClient.RawResponse resp = clientFor(server).sendStreaming(
                "model", List.of(new ClaudeMessage(ClaudeRole.USER, "hi")), deltas::add);

        assertEquals(List.of("Hello", ", world"), deltas, "each text_delta should be delivered in order");
        assertEquals("Hello, world", resp.text());
        assertEquals(12, resp.inputTokens());
        assertEquals(3, resp.cacheReadTokens());
        assertEquals(7, resp.outputTokens(), "final output tokens come from message_delta");

        RecordedRequest recorded = server.takeRequest();
        assertTrue(recorded.getBody().readUtf8().contains("\"stream\":true"), "request must enable streaming");
    }

    @Test
    void streamingRetriesOnTransientErrorThenStreams() throws IOException {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"));
        server.enqueue(sse(SSE));

        List<String> deltas = new ArrayList<>();
        OKHttpClaudeClient.RawResponse resp = clientFor(server).sendStreaming(
                "model", List.of(new ClaudeMessage(ClaudeRole.USER, "hi")), deltas::add);

        assertEquals("Hello, world", resp.text());
        assertEquals(2, server.getRequestCount(), "should retry the transient error, then stream");
    }
}
