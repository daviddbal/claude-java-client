package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeRunnerCachingTest {

    // Collector whose returned files are based on classes for unique hashes
    static class DummySourceFileCollector extends SourceFileCollector {
        DummySourceFileCollector() { super(new SourceRootConfig(List.of())); }
        @Override
        public List<SourceFile> collect(List<Class<?>> classes) {
            List<SourceFile> files = new ArrayList<>();
            for (Class<?> clazz : classes) {
                files.add(new SourceFile(
                        Path.of(clazz.getName() + ".java"),
                        "class " + clazz.getSimpleName() + " {}")
                );
            }
            return files;
        }
    }

    // Context collector whose returned files are settable for each test
    static class DummyContextFileCollector extends ContextFileCollector {
        private final List<SourceFile> contextFiles;
        DummyContextFileCollector(Path root, List<SourceFile> files) {
            super(root);
            this.contextFiles = files;
        }
        @Override
        public List<SourceFile> collect() { return contextFiles; }
    }

    static class CountingFactory implements ClaudeClientFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private int callCount = 0;
        private ClaudeClientConfig lastConfig;
        private final List<ClaudeClientConfig> configHistory = new ArrayList<>();
        @Override
        public ClaudeClient createClient(ClaudeClientConfig config) {
            callCount++;
            lastConfig = config;
            configHistory.add(config);
            // Distinct instance on each call
            return new TestClaudeClient(counter.getAndIncrement());
        }
        void reset() { callCount = 0; lastConfig = null; configHistory.clear(); counter.set(0); }
        int callCount() { return callCount; }
        ClaudeClientConfig lastConfig() { return lastConfig; }
        List<ClaudeClientConfig> configHistory() { return configHistory; }
    }

    static class TestClaudeClient extends ClaudeClient {
        private final int id;
        public TestClaudeClient(int id) { super("key", id, ""); this.id = id; }
        public int getId() { return id; }
    }

    private DummySourceFileCollector sourceCollector;
    private DummyContextFileCollector contextCollector;
    private ConversationStore store;
    private ClaudeRunner runner;
    private CountingFactory clientFactory;
    private static final Path CONTEXT_ROOT = Path.of("context-files");

    @BeforeEach
    void setUp() {
        sourceCollector = new DummySourceFileCollector();
        contextCollector = new DummyContextFileCollector(CONTEXT_ROOT, List.of());
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

    @Test
    void rebuildsClientWhenContextChanges() throws IOException {
        runner.setContext(List.of(String.class));
        ClaudeClient firstClient = runner.getClientForTest();

        runner.setContext(List.of(Integer.class));
        ClaudeClient secondClient = runner.getClientForTest();

        assertNotSame(firstClient, secondClient, "Client should be rebuilt on context change");
        assertEquals(2, clientFactory.callCount());
    }

    @Test
    void doesNotRebuildClientWhenContextIsSame() throws IOException {
        runner.setContext(List.of(String.class));
        ClaudeClient firstClient = runner.getClientForTest();

        runner.setContext(List.of(String.class));
        ClaudeClient secondClient = runner.getClientForTest();

        assertSame(firstClient, secondClient, "Client should not rebuild when context is unchanged");
        assertEquals(1, clientFactory.callCount());
    }

    @Test
    void systemPromptIsCachedAndPassedToClient() throws IOException {
        SourceFile sf = new SourceFile(Path.of("Test.java"), "class Test {}");
        sourceCollector = new DummySourceFileCollector() {
            @Override
            public List<SourceFile> collect(List<Class<?>> classes) {
                return List.of(sf);
            }
        };
        // Re-create runner to inject new sourceCollector
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
        assertNotNull(clientFactory.lastConfig());
        assertTrue(clientFactory.lastConfig().systemPrompt().contains("class Test {}"),
                "System prompt should include source file content");
    }

    @Test
    void conversationStoreRemainsUntouchedOnContextReload() throws IOException {
        store.addUserMessage("Hello");
        runner.setContext(List.of(String.class));
        assertEquals(1, store.getTurnCount(), "Conversation store should not be cleared by context reload");
    }

    @Test
    void clientFactoryReceivesCorrectConfig() throws IOException {
        SourceFile sf = new SourceFile(Path.of("Test.java"), "class Test {}");
        sourceCollector = new DummySourceFileCollector() {
            @Override
            public List<SourceFile> collect(List<Class<?>> classes) {
                return List.of(sf);
            }
        };
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