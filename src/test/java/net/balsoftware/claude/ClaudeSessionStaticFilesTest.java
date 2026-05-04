package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeSessionStaticFilesTest {

    private ClaudeSession session;
    private ContextFileCollector spyContext;

    @BeforeEach
    void setUp() throws IOException {
        // Create a spy of the real collector
        spyContext = spy(new ContextFileCollector(Path.of("context-files")));

        // Build session using the spy
        session = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(config -> new OKHttpClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()))
                .contextFileCollector(spyContext)  // inject the spy
                .build();
    }

    @Test
    void runnerIsRebuiltWhenStaticFilesChange() throws IOException {
        // First call returns one file
        SourceFile file1 = new SourceFile(Path.of("A.java"), "class A {}");
        doReturn(List.of(file1)).when(spyContext).collect();

        session.loadContext(List.of());  // load initial context
        ClaudeRunner firstRunner = session.getRunner();

        // Change the static files (simulate update)
        SourceFile file2 = new SourceFile(Path.of("B.java"), "class B {}");
        doReturn(List.of(file2)).when(spyContext).collect();

        session.loadContext(List.of());  // reload context with "new" static files
        ClaudeRunner secondRunner = session.getRunner();

        assertNotSame(firstRunner, secondRunner, "Runner should be rebuilt when static context files change");
        assertTrue(secondRunner.getCachedSystemPrompt().contains("class B {}"));
    }
}