package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeSessionPromptCacheTest {

    private ClaudeSession session;
    private FakeClaudeClientFactory fakeFactory;

    @BeforeEach
    void setup() {
        fakeFactory = new FakeClaudeClientFactory();

        session = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(fakeFactory)
                .contextFileCollector(new InMemoryContextCollector()) // no real files
                .sourceFileCollector(new InMemorySourceCollector())    // no real files
                .build();
    }

    @Test
    void testPromptCaching() throws IOException {
        List<Class<?>> contextA = List.of(String.class);
        List<Class<?>> contextB = List.of(Integer.class);

        // First call, cache miss
        session.loadContext(contextA);
        String prompt1 = session.getRunner().getCachedSystemPrompt();
        ClaudeClient client1 = session.getRunner().getClientForTest();

        // Second call with same context, should hit cache (same prompt, same client)
        session.loadContext(contextA);
        String prompt2 = session.getRunner().getCachedSystemPrompt();
        ClaudeClient client2 = session.getRunner().getClientForTest();

        assertSame(client1, client2, "Client should be reused when context unchanged");
        assertEquals(prompt1, prompt2, "System prompt should be reused from cache");

        // Third call with different context, should rebuild
        session.loadContext(contextB);
        String prompt3 = session.getRunner().getCachedSystemPrompt();
        ClaudeClient client3 = session.getRunner().getClientForTest();

        assertNotSame(client1, client3, "Client should be rebuilt for new context");
        assertNotEquals(prompt1, prompt3, "System prompt should be rebuilt for new context");
    }

    // ----------------- Fake / In-memory helpers -----------------

    static class FakeClaudeClientFactory implements ClaudeClientFactory {
        @Override
        public ClaudeClient createClient(ClaudeClientConfig config) {
            return new FakeClaudeClient(config);
        }
    }

    static class FakeClaudeClient extends ClaudeClient {
        FakeClaudeClient(ClaudeClientConfig config) {
            super(config.apiKey(), config.maxTokens(), config.systemPrompt());
        }

        @Override
        public RawResponse send(String model, List<ClaudeMessage> conversation) {
            // always return dummy response with 0 tokens
            return new RawResponse("ok", 0, 0, 0, 0);
        }
    }

    static class InMemoryContextCollector extends ContextFileCollector {
        InMemoryContextCollector() {
            super(Path.of("context-files"));
        }

        @Override
        public List<SourceFile> collect() {
            // return fixed static files
            return List.of(
                    new SourceFile(Path.of("A.txt"), "contentA"),
                    new SourceFile(Path.of("B.txt"), "contentB")
            );
        }
    }

    static class InMemorySourceCollector extends SourceFileCollector {
        InMemorySourceCollector() {
            super(new SourceRootConfig(List.of(Path.of("src"))));
        }

        @Override
        public List<SourceFile> collect(List<Class<?>> classes) {
            // just return a dummy source file for each class
            return classes.stream()
                    .map(c -> new SourceFile(Path.of(c.getSimpleName() + ".java"), "// dummy"))
                    .toList();
        }
    }
}