package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers conversation-history content (generated files) and the stdout logging toggle.
 */
class ClaudeSessionHistoryTest {

    /** Client that records the conversation it receives and always returns a one-file response. */
    private static ClaudeClientFactory fileReturningFactory(AtomicReference<List<ClaudeMessage>> capture) {
        return config -> new OKHttpClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()) {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {
                capture.set(new ArrayList<>(conversation));
                return new RawResponse(
                        "{\"type\":\"code\",\"description\":\"done\","
                                + "\"files\":[{\"path\":\"Foo.java\",\"content\":\"class Foo {}\"}]}",
                        10, 5, 0, 0
                );
            }
        };
    }

    @Test
    void historyIncludesGeneratedFileContentByDefault() throws IOException {
        AtomicReference<List<ClaudeMessage>> lastConversation = new AtomicReference<>();
        ClaudeSession session = ClaudeSession.builder()
                .apiKey("k")
                .clientFactory(fileReturningFactory(lastConversation))
                .build();

        session.loadContext(List.of());
        session.ask("make a class");
        session.ask("now add a method");

        // conversation seen on the 2nd ask: [USER, ASSISTANT(history), USER]
        List<ClaudeMessage> conv = lastConversation.get();
        assertEquals(ClaudeRole.ASSISTANT, conv.get(1).role());
        String assistant = conv.get(1).content();
        assertTrue(assistant.contains("Foo.java"), assistant);
        assertTrue(assistant.contains("class Foo {}"),
                "generated code should be replayed in history: " + assistant);
    }

    @Test
    void historyOmitsFileContentWhenDisabled() throws IOException {
        AtomicReference<List<ClaudeMessage>> lastConversation = new AtomicReference<>();
        ClaudeSession session = ClaudeSession.builder()
                .apiKey("k")
                .includeFileContentInHistory(false)
                .clientFactory(fileReturningFactory(lastConversation))
                .build();

        session.loadContext(List.of());
        session.ask("make a class");
        session.ask("again");

        String assistant = lastConversation.get().get(1).content();
        assertTrue(assistant.contains("[files: "), "should record the file-path summary: " + assistant);
        assertTrue(assistant.contains("Foo.java"), assistant);
        assertFalse(assistant.contains("class Foo {}"), "file content should be omitted: " + assistant);
    }

    @Test
    void doesNotLogToStdoutByDefault() throws IOException {
        assertFalse(captureStdoutOfOneAsk(false).contains("[Claude]"),
                "library should not print to stdout by default");
    }

    @Test
    void logsToStdoutWhenEnabled() throws IOException {
        assertTrue(captureStdoutOfOneAsk(true).contains("[Claude]"),
                "enabling logResponses should print to stdout");
    }

    private static String captureStdoutOfOneAsk(boolean logResponses) throws IOException {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            ClaudeSession session = ClaudeSession.builder()
                    .apiKey("k")
                    .logResponses(logResponses)
                    .clientFactory(fileReturningFactory(new AtomicReference<>()))
                    .build();
            session.loadContext(List.of());
            session.ask("hi");
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
