package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeRunnerTest {

    private ClaudeClient mockClient;
    private SourceFileCollector mockCollector;
    private ContextFileCollector mockContextCollector;
    private ConversationStore store;
    private ClaudeRunner runner;

    private static final Path CONTEXT_ROOT = Path.of("context-files");

    @BeforeEach
    void setUp() throws IOException {
        mockClient           = mock(ClaudeClient.class);
        mockCollector        = mock(SourceFileCollector.class);
        mockContextCollector = mock(ContextFileCollector.class);
        store                = new ConversationStore();

        when(mockContextCollector.getContextRoot()).thenReturn(CONTEXT_ROOT);
        when(mockContextCollector.collect()).thenReturn(List.of());
        when(mockCollector.collect(anyList())).thenReturn(List.of());

        runner = new ClaudeRunner(mockClient, mockCollector, mockContextCollector, store, new ClaudeResponseParser());
    }

    // ------------------------------------------------------------------ helpers

    /** Builds a RawResponse with zero cache tokens — keeps test call sites concise. */
    private static ClaudeClient.RawResponse rawResponse(String text, int inputTokens, int outputTokens) {
        return new ClaudeClient.RawResponse(text, inputTokens, outputTokens, 0, 0);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void parsesResponseCorrectly() throws IOException {
        String json = """
                { "description": "done", "files": [{ "path": "X.java", "content": "class X {}" }] }
                """;
        when(mockClient.send(anyString(), anyList()))
                .thenReturn(rawResponse(json, 200, 80));

        ClaudeResponse r = runner.run(ClaudeModel.HAIKU, "Do something");
        assertEquals("done", r.description());
        assertEquals(1, r.files().size());
        assertEquals(200, r.inputTokens());
        assertEquals(80,  r.outputTokens());
    }

    @Test
    void storesCompactSummaryNotFullJson() throws IOException {
        String json = """
                { "description": "Added toString", "files": [{ "path": "Foo.java", "content": "..." }] }
                """;
        when(mockClient.send(anyString(), anyList()))
                .thenReturn(rawResponse(json, 100, 50));

        runner.run(ClaudeModel.HAIKU, "Add toString");

        List<ClaudeMessage> msgs = store.getMessages();
        ClaudeMessage assistantMsg = msgs.stream()
                .filter(m -> m.role() == ClaudeRole.ASSISTANT)
                .findFirst().orElseThrow();

        assertFalse(assistantMsg.content().contains("\"files\""),
                "Full JSON should not be stored in history");
        assertTrue(assistantMsg.content().contains("Foo.java"),
                "Summary should mention the file path");
    }

    @Test
    void preservesMultipleTurnsInHistory() throws IOException {
        String json = """
                { "description": "ok", "files": [] }""";
        when(mockClient.send(anyString(), anyList()))
                .thenReturn(rawResponse(json, 10, 5));

        runner.run(ClaudeModel.HAIKU, "Turn 1");
        runner.run(ClaudeModel.HAIKU, "Turn 2");

        assertEquals(4, store.getTurnCount());
    }

    @Test
    void propagatesClientException() throws IOException {
        when(mockClient.send(anyString(), anyList()))
                .thenThrow(new IOException("network error"));
        assertThrows(IOException.class, () -> runner.run(ClaudeModel.HAIKU, "fail"));
    }

    @Test
    void cacheTokensArePassedThrough() throws IOException {
        String json = """
                { "description": "ok", "files": [] }""";
        when(mockClient.send(anyString(), anyList()))
                .thenReturn(new ClaudeClient.RawResponse(json, 50, 20, 980, 0));

        ClaudeResponse r = runner.run(ClaudeModel.HAIKU, "First call — cache written");
        assertEquals(980, r.cacheCreationTokens());
        assertEquals(0,   r.cacheReadTokens());

        when(mockClient.send(anyString(), anyList()))
                .thenReturn(new ClaudeClient.RawResponse(json, 50, 20, 0, 980));

        ClaudeResponse r2 = runner.run(ClaudeModel.HAIKU, "Second call — cache hit");
        assertEquals(0,   r2.cacheCreationTokens());
        assertEquals(980, r2.cacheReadTokens());
    }

    @Test
    void setContextEmbeddedInSystemPrompt() throws IOException {
        SourceFile sf = new SourceFile(Path.of("Foo.java"), "class Foo {}");
        when(mockCollector.collect(anyList())).thenReturn(List.of(sf));

        runner.setContext(List.of(String.class));

        assertTrue(store.getSystemPrompt().contains("class Foo {}"),
                "Source file content should be in system prompt");
        assertEquals(0, store.getTurnCount(),
                "setContext should not add a conversation turn");
    }

    @Test
    void setContextIncludesContextDirectoryFiles() throws IOException {
        SourceFile contextFile = new SourceFile(CONTEXT_ROOT.resolve("schema.sql"), "SELECT 1;");
        when(mockContextCollector.collect()).thenReturn(List.of(contextFile));

        runner.setContext(List.of());

        assertTrue(store.getSystemPrompt().contains("SELECT 1;"),
                "Context-directory file content should be in system prompt");
    }

    @Test
    void subdirectoryFileGetsLabelHeader() throws IOException {
        SourceFile legacyFile = new SourceFile(
                CONTEXT_ROOT.resolve("legacy").resolve("OldService.java"),
                "class OldService {}"
        );
        when(mockContextCollector.collect()).thenReturn(List.of(legacyFile));

        runner.setContext(List.of());

        String prompt = store.getSystemPrompt();
        assertTrue(prompt.contains("=== [legacy] ==="),
                "Subdirectory label header should appear in system prompt");
        assertTrue(prompt.contains("class OldService {}"),
                "File content should appear in system prompt");
    }

    @Test
    void rootLevelFileHasNoLabelHeader() throws IOException {
        SourceFile rootFile = new SourceFile(
                CONTEXT_ROOT.resolve("notes.md"),
                "# Notes"
        );
        when(mockContextCollector.collect()).thenReturn(List.of(rootFile));

        runner.setContext(List.of());

        String prompt = store.getSystemPrompt();
        assertFalse(prompt.contains("=== ["),
                "Root-level file should not produce a group label header");
        assertTrue(prompt.contains("# Notes"),
                "Root-level file content should still appear in system prompt");
    }

    @Test
    void multipleSubdirectoriesGetSeparateLabels() throws IOException {
        SourceFile legacyFile = new SourceFile(
                CONTEXT_ROOT.resolve("legacy").resolve("Old.java"), "class Old {}"
        );
        SourceFile newFile = new SourceFile(
                CONTEXT_ROOT.resolve("new implementation").resolve("New.java"), "class New {}"
        );
        when(mockContextCollector.collect()).thenReturn(List.of(legacyFile, newFile));

        runner.setContext(List.of());

        String prompt = store.getSystemPrompt();
        assertTrue(prompt.contains("=== [legacy] ==="),             "Should have legacy label");
        assertTrue(prompt.contains("=== [new implementation] ==="), "Should have 'new implementation' label");
        assertTrue(prompt.contains("class Old {}"),                 "Legacy file content should be present");
        assertTrue(prompt.contains("class New {}"),                 "New file content should be present");
    }
}