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
          "files": []
        }
        """;

        ClaudeStructuredResponse r = parser.parseStructured(json);
        assertTrue(r.files().isEmpty());
        assertEquals("Nothing", r.description());

        // There are no files, so content() returns null
        assertNull(r.content());
    }

    @Test
    void missingDescriptionAndFilesThrows() {
        String json = """
        {
          "type": "explanation",
          "files": []
        }
        """;

        // Expect an exception because explanation has no content
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            parser.parseStructured(json);
        });
    }

    @Test
    void parsesCodeWithExplanation() throws IOException {
        String json = """
            {
              "type": "code_with_explanation",
              "description": "Example with explanation",
              "files": [
                { "path": "Example.java", "content": "class Example {}" }
              ]
            }
            """;

        ClaudeStructuredResponse r = parser.parseStructured(json);

        assertEquals("Example with explanation", r.description());

        assertEquals(1, r.files().size());
        assertEquals("Example.java", r.files().get(0).path());
        assertEquals("class Example {}", r.files().get(0).content());
    }
}