package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeResponseParserTest {

    private final ClaudeResponseParser parser = new ClaudeResponseParser();

    @Test
    void parsesDescriptionAndFiles() throws IOException {
        String json = """
                {
                  "description": "Added toString method",
                  "files": [
                    { "path": "src/Foo.java", "content": "public class Foo {}" }
                  ]
                }
                """;
        ClaudeResponse r = parser.parse(json, 100, 50);
        assertEquals("Added toString method", r.description());
        assertEquals(1, r.files().size());
        assertEquals("src/Foo.java", r.files().get(0).path());
        assertEquals(100, r.inputTokens());
        assertEquals(50,  r.outputTokens());
    }

    @Test
    void parsesMultipleFiles() throws IOException {
        String json = """
                { "description": "Two files", "files": [
                    { "path": "A.java", "content": "class A {}" },
                    { "path": "B.java", "content": "class B {}" }
                ]}""";
        assertEquals(2, parser.parse(json).files().size());
    }

    @Test
    void parsesEmptyFiles() throws IOException {
        String json = """
                { "description": "Nothing", "files": [] }""";
        ClaudeResponse r = parser.parse(json);
        assertTrue(r.files().isEmpty());
        assertEquals(0, r.inputTokens());
    }

    @Test
    void missingDescriptionDefaultsToEmpty() throws IOException {
        assertEquals("", parser.parse("""
                { "files": [] }""").description());
    }
}