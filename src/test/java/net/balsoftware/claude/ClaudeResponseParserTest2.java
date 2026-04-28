package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeResponseParserTest2 {

    @Test
    void testKnownBrokenClaudeResponse() throws Exception {
        // Load a real problematic response
        Path p = Path.of("src/test/resources/claude-responses/fail-001.txt");
        String raw = Files.readString(p);

        ClaudeResponseParser parser = new ClaudeResponseParser();
        ClaudeStructuredResponse resp = parser.parseStructured(raw);

        // Assert that parsing did not fail completely
        assertNotNull(resp.description(), "Description should not be null");

        // For debugging, print out what it parsed
        System.out.println("Parsed Claude description: " + resp.description());
        System.out.println("Number of files: " + resp.files().size());

        // Optional: check fallback behavior for missing files
        assertNotNull(resp.files(), "Files list should not be null");
    }
}