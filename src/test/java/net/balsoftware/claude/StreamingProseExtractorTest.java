package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamingProseExtractorTest {

    /** Feeds the given chunks and returns the accumulated prose. */
    private static String extract(String... chunks) {
        StringBuilder out = new StringBuilder();
        StreamingProseExtractor ex = new StreamingProseExtractor(out::append);
        for (String c : chunks) {
            ex.accept(c);
        }
        return out.toString();
    }

    @Test
    void extractsTopLevelDescription() {
        assertEquals("Hello world",
                extract("{\"type\":\"explanation\",\"description\":\"Hello world\",\"files\":[]}"));
    }

    @Test
    void worksWhenFedOneCharacterAtATime() {
        String json = "{\"type\":\"explanation\",\"description\":\"Hello world\",\"files\":[]}";
        StringBuilder out = new StringBuilder();
        StreamingProseExtractor ex = new StreamingProseExtractor(out::append);
        for (char c : json.toCharArray()) {
            ex.accept(String.valueOf(c));
        }
        assertEquals("Hello world", out.toString());
    }

    @Test
    void decodesEscapeSequences() {
        // JSON value: line1\nline2 "q" \ / end
        String json = "{\"description\":\"line1\\nline2 \\\"q\\\" \\\\ \\/ end\"}";
        assertEquals("line1\nline2 \"q\" \\ / end", extract(json));
    }

    @Test
    void decodesUnicodeEscapeSplitAcrossChunks() {
        // {"description":"café"} split mid-escape
        assertEquals("café", extract("{\"description\":\"caf\\u00", "e9\"}"));
    }

    @Test
    void ignoresOtherFieldsAndStructuralCharsInsideDescription() {
        // description contains braces and escaped quotes; file content must not leak through.
        String json = "{\"type\":\"code\",\"description\":\"use {x} and \\\"y\\\"\","
                + "\"files\":[{\"path\":\"A.java\",\"content\":\"class A {}\"}]}";
        assertEquals("use {x} and \"y\"", extract(json));
    }

    @Test
    void worksWhenDescriptionComesAfterFiles() {
        String json = "{\"files\":[{\"path\":\"A\",\"content\":\"x\"}],"
                + "\"type\":\"code\",\"description\":\"after\"}";
        assertEquals("after", extract(json));
    }

    @Test
    void ignoresMarkdownCodeFenceWrapping() {
        assertEquals("hi", extract("```json\n{\"description\":\"hi\"}\n```"));
    }
}
