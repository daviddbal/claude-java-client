package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeRunnerCachingTest {

    /**
     * Simple configurable source collector stub.
     */
    static class StubSourceFileCollector extends SourceFileCollector {

        private List<SourceFile> files;

        StubSourceFileCollector(List<SourceFile> files) {
            super(new SourceRootConfig(List.of()));
            this.files = files;
        }

        @Override
        public List<SourceFile> collect(List<Class<?>> classes) {
            return files;
        }

        void setFiles(List<SourceFile> files) {
            this.files = files;
        }
    }

    /**
     * Simple context collector stub.
     */
    static class StubContextFileCollector extends ContextFileCollector {

        private List<SourceFile> files;

        StubContextFileCollector(Path root, List<SourceFile> files) {
            super(root);
            this.files = files;
        }

        @Override
        public List<SourceFile> collect() {
            return files;
        }

        void setFiles(List<SourceFile> files) {
            this.files = files;
        }
    }

    static class NoopClaudeClient implements ClaudeClient {

        private final int id;

        NoopClaudeClient(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        @Override
        public OKHttpClaudeClient.RawResponse send(String model, List<ClaudeMessage> messages) {
            return new OKHttpClaudeClient.RawResponse(
                    "noop",
                    1,
                    1,
                    0,
                    0
            );
        }
    }

    /**
     * Observability hook: tracks client creation + config changes.
     */
    static class CountingFactory implements ClaudeClientFactory {

        private final AtomicInteger counter = new AtomicInteger();
        private int callCount = 0;
        private ClaudeClientConfig lastConfig;

        @Override
        public ClaudeClient createClient(ClaudeClientConfig config) {
            callCount++;
            lastConfig = config;

            // We do NOT need a real client implementation here
            return new NoopClaudeClient(counter.getAndIncrement());
        }

        void reset() {
            callCount = 0;
            lastConfig = null;
            counter.set(0);
        }

        int callCount() {
            return callCount;
        }

        ClaudeClientConfig lastConfig() {
            return lastConfig;
        }
    }

    private StubSourceFileCollector sourceCollector;
    private StubContextFileCollector contextCollector;
    private ConversationStore store;
    private ClaudeRunner runner;
    private CountingFactory clientFactory;

    private static final Path CONTEXT_ROOT = Path.of("context-files");

    @BeforeEach
    void setUp() {

        sourceCollector = new StubSourceFileCollector(List.of());
        contextCollector = new StubContextFileCollector(CONTEXT_ROOT, List.of());

        store = new ConversationStore();
        clientFactory = new CountingFactory();

        runner = new ClaudeRunner(
                clientFactory,
                sourceCollector,
                contextCollector,
                store,
                new ClaudeResponseParser(),
                new ClaudeClientConfig("dummy-api-key", 4096, true, ""),
                List.of()
        );

        clientFactory.reset();
    }

    // ------------------------------------------------------------
    // SYSTEM PROMPT GENERATION
    // ------------------------------------------------------------

    @Test
    void systemPromptIsCachedAndPassedToClient() throws IOException {

        SourceFile sf = new SourceFile(
                Path.of("Test.java"),
                "class Test {}"
        );

        sourceCollector.setFiles(List.of(sf));

        runner = new ClaudeRunner(
                clientFactory,
                sourceCollector,
                contextCollector,
                store,
                new ClaudeResponseParser(),
                new ClaudeClientConfig("dummy-api-key", 4096, true, ""),
                List.of()
        );

        runner.setContext(List.of(String.class));

        ClaudeClientConfig config = clientFactory.lastConfig();

        assertNotNull(config);
        assertTrue(config.systemPrompt().contains("class Test {}"));
    }

    // ------------------------------------------------------------
    // CONVERSATION SAFETY
    // ------------------------------------------------------------

    @Test
    void conversationStoreRemainsUntouchedOnContextReload() throws IOException {

        store.addUserMessage("Hello");

        runner.setContext(List.of(String.class));

        assertEquals(1, store.getTurnCount());
    }

    // ------------------------------------------------------------
    // FACTORY CONFIG VALIDATION
    // ------------------------------------------------------------

    @Test
    void clientFactoryReceivesCorrectConfig() throws IOException {

        SourceFile sf = new SourceFile(
                Path.of("Test.java"),
                "class Test {}"
        );

        sourceCollector.setFiles(List.of(sf));

        runner = new ClaudeRunner(
                clientFactory,
                sourceCollector,
                contextCollector,
                store,
                new ClaudeResponseParser(),
                new ClaudeClientConfig("dummy-api-key", 4096, true, ""),
                List.of()
        );

        runner.setContext(List.of(String.class));

        ClaudeClientConfig config = clientFactory.lastConfig();

        assertNotNull(config);
        assertEquals("dummy-api-key", config.apiKey());
        assertEquals(4096, config.maxTokens());
        assertTrue(config.enableCaching());
        assertTrue(config.systemPrompt().contains("class Test {}"));
    }
}