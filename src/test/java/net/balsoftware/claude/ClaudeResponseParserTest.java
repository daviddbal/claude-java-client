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
                  "type": "code",
                  "description": "Added toString method",
                  "files": [
                    { "path": "src/Foo.java", "content": "public class Foo {}" }
                  ]
                }
                """;
        ClaudeStructuredResponse r = parser.parseStructured(json);

        assertEquals("Added toString method", r.description());
        assertEquals(1, r.files().size());
        assertEquals("src/Foo.java", r.files().get(0).path());
        assertEquals("public class Foo {}", r.files().get(0).content());
    }

    @Test
    void parsesMultipleFiles() throws IOException {
        String json = """
                {
                  "type": "code",
                  "description": "Two files",
                  "files": [
                    { "path": "A.java", "content": "class A {}" },
                    { "path": "B.java", "content": "class B {}" }
                  ]
                }
                """;

        ClaudeStructuredResponse r = parser.parseStructured(json);
        assertEquals(2, r.files().size());
        assertEquals("A.java", r.files().get(0).path());
        assertEquals("B.java", r.files().get(1).path());
    }

    @Test
    void parsesEmptyFiles() throws IOException {
        String json = """
            {
              "type": "explanation",
              "description": "Nothing",
              "content": "Nothing to explain",
              "files": []
            }
            """;

        ClaudeStructuredResponse r = parser.parseStructured(json);
        assertTrue(r.files().isEmpty());
        assertEquals("Nothing", r.description());
        assertEquals("Nothing to explain", r.content());
    }

    @Test
    void missingDescriptionDefaultsToEmpty() throws IOException {
        String json = """
                {
                  "type": "explanation",
                  "files": []
                }
                """;

        ClaudeStructuredResponse r = parser.parseStructured(json);
        assertEquals("", r.description() == null ? "" : r.description());
        assertTrue(r.files().isEmpty());
    }

    @Test
    void parsesCodeWithExplanation() throws IOException {
        String json = """
                {
                  "type": "code_with_explanation",
                  "description": "Example with explanation",
                  "explanation": "This explains the code",
                  "files": [
                    { "path": "Example.java", "content": "class Example {}" }
                  ]
                }
                """;

        ClaudeStructuredResponse r = parser.parseStructured(json);
        assertEquals("Example with explanation", r.description());
        assertEquals("This explains the code", r.explanation());
        assertEquals(1, r.files().size());
    }
}