package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ClaudeRunnerTest {

    private ClaudeClientFactory mockFactory;
    private ClaudeClient mockClient;
    private SourceFileCollector mockCollector;
    private ContextFileCollector mockContextCollector;
    private ConversationStore store;
    private ClaudeRunner runner;
    private ClaudeClientConfig initialConfig;

    private static final Path CONTEXT_ROOT = Path.of("context-files");

    @BeforeEach
    void setUp() throws IOException {
        mockClient           = mock(ClaudeClient.class);
        mockCollector        = mock(SourceFileCollector.class);
        mockContextCollector = mock(ContextFileCollector.class);
        store                = new ConversationStore();

        mockFactory = config -> mockClient;
        initialConfig = new ClaudeClientConfig("test-api-key", 8192, true, "");

        when(mockContextCollector.getContextRoot()).thenReturn(CONTEXT_ROOT);
        when(mockContextCollector.collect()).thenReturn(List.of());
        when(mockCollector.collect(anyList())).thenReturn(List.of());

        // Pass empty list for initial contextClasses
        runner = new ClaudeRunner(
                mockFactory,
                mockCollector,
                mockContextCollector,
                store,
                new ClaudeResponseParser(),
                initialConfig,
                List.of() // <--- updated for new constructor
        );
    }

    // ------------------------------------------------------------------ helpers

    private static ClaudeClient.RawResponse rawResponse(String text, int inputTokens, int outputTokens) {
        return new ClaudeClient.RawResponse(text, inputTokens, outputTokens, 0, 0);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void parsesResponseCorrectly() throws IOException {
        String json = """
            {
              "type": "code",
              "description": "done",
              "files": [
                { "path": "X.java", "content": "class X {}" }
              ]
            }
            """;

        when(mockClient.send(anyString(), anyList()))
                .thenReturn(rawResponse(json, 200, 80));

        runner.setContext(List.of(String.class));
        ClaudeStructuredResponseWithTokens r = runner.runStructured(ClaudeModel.defaultModel().id(), "Do something");

        assertEquals("done", r.description());
        assertEquals(1, r.files().size());
        assertEquals(200, r.inputTokens());
        assertEquals(80, r.outputTokens());
    }

    @Test
    void preservesMultipleTurnsInHistory() throws IOException {
        String json = "{ \"description\": \"ok\", \"files\": [] }";
        when(mockClient.send(anyString(), anyList())).thenReturn(rawResponse(json, 10, 5));

        runner.setContext(List.of(String.class));
        runner.runStructured(ClaudeModel.defaultModel().id(), "Turn 1");
        runner.runStructured(ClaudeModel.defaultModel().id(), "Turn 2");

        // Only the assistant messages after run() are added
        assertEquals(4, store.getTurnCount());
    }

    @Test
    void propagatesClientException() throws IOException {
        when(mockClient.send(anyString(), anyList())).thenThrow(new IOException("network error"));
        runner.setContext(List.of(String.class));
        assertThrows(IOException.class, () -> runner.runStructured(ClaudeModel.defaultModel().id(), "fail"));
    }

    @Test
    void cacheTokensArePassedThrough() throws IOException {
        String json = "{ \"description\": \"ok\", \"files\": [] }";
        when(mockClient.send(anyString(), anyList()))
                .thenReturn(new ClaudeClient.RawResponse(json, 50, 20, 980, 0));

        runner.setContext(List.of(String.class));
        ClaudeStructuredResponseWithTokens r = runner.runStructured(ClaudeModel.defaultModel().id(), "First call — cache written");

        assertEquals(980, r.cacheCreationTokens());
        assertEquals(0, r.cacheReadTokens());

        when(mockClient.send(anyString(), anyList()))
                .thenReturn(new ClaudeClient.RawResponse(json, 50, 20, 0, 980));

        ClaudeStructuredResponseWithTokens r2 = runner.runStructured(ClaudeModel.defaultModel().id(), "Second call — cache hit");

        assertEquals(0, r2.cacheCreationTokens());
        assertEquals(980, r2.cacheReadTokens());
    }

    @Test
    void setContextEmbeddedInSystemPrompt() throws IOException {
        SourceFile sf = new SourceFile(Path.of("Foo.java"), "class Foo {}");
        when(mockCollector.collect(anyList())).thenReturn(List.of(sf));

        runner.setContext(List.of(String.class));

        assertTrue(runner.getCachedSystemPrompt().contains("class Foo {}"),
                "Source file content should appear in cached system prompt");
    }

    @Test
    void setContextIncludesContextDirectoryFiles() throws IOException {
        SourceFile contextFile = new SourceFile(CONTEXT_ROOT.resolve("schema.sql"), "SELECT 1;");
        when(mockContextCollector.collect()).thenReturn(List.of(contextFile));

        runner.setContext(List.of());

        assertTrue(runner.getCachedSystemPrompt().contains("SELECT 1;"),
                "Context-directory file content should be in cached system prompt");
    }

    @Test
    void subdirectoryFileGetsLabelHeader() throws IOException {
        SourceFile legacyFile = new SourceFile(CONTEXT_ROOT.resolve("legacy").resolve("OldService.java"), "class OldService {}");
        when(mockContextCollector.collect()).thenReturn(List.of(legacyFile));

        runner.setContext(List.of());

        String prompt = runner.getCachedSystemPrompt();
        assertTrue(prompt.contains("=== [legacy] ==="), "Subdirectory label header should appear in system prompt");
        assertTrue(prompt.contains("class OldService {}"), "File content should appear in system prompt");
    }

    @Test
    void rootLevelFileHasNoLabelHeader() throws IOException {
        SourceFile rootFile = new SourceFile(CONTEXT_ROOT.resolve("notes.md"), "# Notes");
        when(mockContextCollector.collect()).thenReturn(List.of(rootFile));

        runner.setContext(List.of());

        String prompt = runner.getCachedSystemPrompt();
        assertFalse(prompt.contains("=== ["), "Root-level file should not produce a group label header");
        assertTrue(prompt.contains("# Notes"), "File content should appear in system prompt");
    }

    @Test
    void multipleSubdirectoriesGetSeparateLabels() throws IOException {
        SourceFile legacyFile = new SourceFile(CONTEXT_ROOT.resolve("legacy").resolve("Old.java"), "class Old {}");
        SourceFile newFile = new SourceFile(CONTEXT_ROOT.resolve("new implementation").resolve("New.java"), "class New {}");
        when(mockContextCollector.collect()).thenReturn(List.of(legacyFile, newFile));

        runner.setContext(List.of());

        String prompt = runner.getCachedSystemPrompt();
        assertTrue(prompt.contains("=== [legacy] ==="), "Should have legacy label");
        assertTrue(prompt.contains("=== [new implementation] ==="), "Should have 'new implementation' label");
        assertTrue(prompt.contains("class Old {}"), "Legacy file content should be present");
        assertTrue(prompt.contains("class New {}"), "New file content should be present");
    }

    @Test
    void runThrowsIfContextNotSet() {
        ClaudeRunner runner = new ClaudeRunner(mockFactory, mockCollector,
                mockContextCollector, store,
                new ClaudeResponseParser(),
                initialConfig, List.of());
        assertThrows(IllegalStateException.class, () -> runner.runStructured(ClaudeModel.defaultModel().id(), "Hello"));
    }

    @Test
    void runWorksAfterContextSet() throws IOException {
        runner.setContext(List.of(String.class));
        when(mockClient.send(anyString(), anyList())).thenReturn(rawResponse("{}", 0, 0));

        ClaudeStructuredResponseWithTokens response = runner.runStructured(ClaudeModel.defaultModel().id(), "Hello");
        assertNotNull(response);
    }
}