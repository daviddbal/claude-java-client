package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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

    @BeforeEach
    void setUp() throws IOException {
        mockClient           = mock(ClaudeClient.class);
        mockCollector        = mock(SourceFileCollector.class);
        mockContextCollector = mock(ContextFileCollector.class);
        store                = new ConversationStore();

        // Default: context-files directory returns nothing
        when(mockContextCollector.collect()).thenReturn(List.of());

        runner = new ClaudeRunner(mockClient, mockCollector, mockContextCollector, store, new ClaudeResponseParser());
    }

    @Test
    void parsesResponseCorrectly() throws IOException {
        String json = """
                { "description": "done", "files": [{ "path": "X.java", "content": "class X {}" }] }
                """;
        when(mockClient.send(anyString(), anyList()))
                .thenReturn(new ClaudeClient.RawResponse(json, 200, 80));

        ClaudeResponse r = runner.run("claude-opus-4-5", "Do something");
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
                .thenReturn(new ClaudeClient.RawResponse(json, 100, 50));

        runner.run("claude-opus-4-5", "Add toString");

        // The assistant message in history should be a short summary, not the raw JSON
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
                .thenReturn(new ClaudeClient.RawResponse(json, 10, 5));

        runner.run("claude-opus-4-5", "Turn 1");
        runner.run("claude-opus-4-5", "Turn 2");

        // 2 user + 2 assistant turns
        assertEquals(4, store.getTurnCount());
    }

    @Test
    void propagatesClientException() throws IOException {
        when(mockClient.send(anyString(), anyList()))
                .thenThrow(new IOException("network error"));
        assertThrows(IOException.class, () -> runner.run("claude-opus-4-5", "fail"));
    }

    @Test
    void setContextEmbeddedInSystemPrompt() throws IOException {
        SourceFile sf = new SourceFile(java.nio.file.Path.of("Foo.java"), "class Foo {}");
        when(mockCollector.collect(anyList())).thenReturn(List.of(sf));

        runner.setContext(List.of(String.class)); // class doesn't matter — collector is mocked

        assertTrue(store.getSystemPrompt().contains("class Foo {}"),
                "Source file content should be in system prompt");
        assertEquals(0, store.getTurnCount(),
                "setContext should not add a conversation turn");
    }

    @Test
    void setContextIncludesContextDirectoryFiles() throws IOException {
        SourceFile contextFile = new SourceFile(java.nio.file.Path.of("context-files/schema.sql"), "SELECT 1;");
        when(mockCollector.collect(anyList())).thenReturn(List.of());
        when(mockContextCollector.collect()).thenReturn(List.of(contextFile));

        runner.setContext(List.of());

        assertTrue(store.getSystemPrompt().contains("SELECT 1;"),
                "Context-directory file content should be in system prompt");
    }
}