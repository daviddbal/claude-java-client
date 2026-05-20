package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Session-level streaming: callback delivery, structured parse of the accumulated text. */
class ClaudeSessionStreamingTest {

    @Test
    void askStreamingDeliversChunksAndParsesAccumulatedResult() throws IOException {
        // Client that streams the structured JSON in two chunks (split mid-token).
        String part1 = "{\"type\":\"explanation\",\"description\":\"hel";
        String part2 = "lo there\",\"files\":[]}";

        ClaudeClientFactory factory = config ->
                new OKHttpClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()) {
                    @Override
                    public RawResponse sendStreaming(String model, List<ClaudeMessage> messages,
                                                     java.util.function.Consumer<String> onTextDelta) {
                        onTextDelta.accept(part1);
                        onTextDelta.accept(part2);
                        return new RawResponse(part1 + part2, 10, 5, 0, 0);
                    }
                };

        ClaudeSession session = ClaudeSession.builder().apiKey("k").clientFactory(factory).build();
        session.loadContext(List.of());

        StringBuilder streamed = new StringBuilder();
        ClaudeStructuredResponseWithTokens resp = session.askStreaming("hi", streamed::append);

        assertEquals(part1 + part2, streamed.toString(), "all chunks should reach the callback");
        assertEquals("hello there", resp.structured().description(),
                "the accumulated text should be parsed into the structured response");
        assertEquals(1, session.getTurnCount());
    }

    @Test
    void askStreamingProseStreamsDecodedDescription() throws IOException {
        // The structured JSON streams in two chunks; the description contains a \n escape.
        String part1 = "{\"type\":\"explanation\",\"description\":\"Hel";
        String part2 = "lo,\\nworld\",\"files\":[]}";

        ClaudeClientFactory factory = config ->
                new OKHttpClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()) {
                    @Override
                    public RawResponse sendStreaming(String model, List<ClaudeMessage> messages,
                                                     java.util.function.Consumer<String> onTextDelta) {
                        onTextDelta.accept(part1);
                        onTextDelta.accept(part2);
                        return new RawResponse(part1 + part2, 10, 5, 0, 0);
                    }
                };

        ClaudeSession session = ClaudeSession.builder().apiKey("k").clientFactory(factory).build();
        session.loadContext(List.of());

        StringBuilder prose = new StringBuilder();
        ClaudeStructuredResponseWithTokens resp = session.askStreamingProse("hi", prose::append);

        // The callback sees decoded prose (no JSON syntax), matching the parsed description.
        assertEquals("Hello,\nworld", prose.toString());
        assertEquals("Hello,\nworld", resp.structured().description());
    }

    @Test
    void streamingDeliversCachedContentOnCacheHit() throws IOException {
        String json = "{\"type\":\"explanation\",\"description\":\"cached answer\",\"files\":[]}";
        ClaudeClientFactory factory = config ->
                new OKHttpClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()) {
                    @Override
                    public RawResponse sendStreaming(String model, List<ClaudeMessage> messages,
                                                     java.util.function.Consumer<String> onTextDelta) {
                        onTextDelta.accept(json);
                        return new RawResponse(json, 10, 5, 0, 0);
                    }
                };

        ClaudeSession session = ClaudeSession.builder().apiKey("k").clientFactory(factory).build();
        session.loadContext(List.of());

        // Prime the cache (miss), then reset so the identical ask is a cache hit.
        session.askStreamingProse("q", s -> { });
        session.resetConversation();

        StringBuilder prose = new StringBuilder();
        ClaudeStructuredResponseWithTokens resp = session.askStreamingProse("q", prose::append);

        assertTrue(resp.isCacheHit(), "the second identical ask should hit the cache");
        assertEquals("cached answer", prose.toString(),
                "cached content must still reach the streaming callback");
    }

    @Test
    void askStreamingFallsBackForNonStreamingClient() throws IOException {
        // A plain ClaudeClient implementing only send(): the interface's default sendStreaming
        // emits the whole text once.
        ClaudeClient plain = (model, messages) -> new OKHttpClaudeClient.RawResponse(
                "{\"type\":\"explanation\",\"description\":\"once\",\"files\":[]}", 7, 3, 0, 0);

        ClaudeSession session = ClaudeSession.builder()
                .apiKey("k")
                .clientFactory(config -> plain)
                .build();
        session.loadContext(List.of());

        List<String> chunks = new ArrayList<>();
        ClaudeStructuredResponseWithTokens resp = session.askStreaming("hi", chunks::add);

        assertEquals(1, chunks.size(), "default fallback emits the whole text exactly once");
        assertEquals("once", resp.structured().description());
        assertEquals(1, session.getTurnCount());
    }
}
