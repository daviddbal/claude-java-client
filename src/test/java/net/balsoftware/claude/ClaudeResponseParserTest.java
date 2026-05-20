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

        // Expect an IOException wrapping IllegalStateException
        IOException ex = assertThrows(IOException.class, () -> {
            parser.parseStructured(json);
        });
        assertTrue(ex.getMessage().contains("Failed to parse LLM JSON response"));
    }

    @Test
    void parsesCodeWhoseContentHasUnbalancedBraces() throws IOException {
        // The content string contains a stray '}' (e.g. inside a string literal). A
        // brace-counting extractor would mis-balance and truncate the JSON; parsing the
        // whole payload directly handles it.
        String json = """
                {"type":"code","description":"tricky","files":[{"path":"X.java","content":"String s = \\"}\\"; // closing brace in a string"}]}
                """;

        ClaudeStructuredResponse r = parser.parseStructured(json);

        assertEquals(1, r.files().size());
        assertEquals("X.java", r.files().get(0).path());
        assertTrue(r.files().get(0).content().contains("String s ="));
    }

    @Test
    void parsesJsonSurroundedByProse() throws IOException {
        // Falls back to extraction when the model wraps JSON in chatter.
        String text = """
                Sure! Here is the file:
                {"type":"code","description":"d","files":[{"path":"P.java","content":"class P {}"}]}
                Hope that helps.
                """;

        ClaudeStructuredResponse r = parser.parseStructured(text);

        assertEquals(1, r.files().size());
        assertEquals("P.java", r.files().get(0).path());
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
