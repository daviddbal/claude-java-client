package net.balsoftware.claude;

import net.balsoftware.claude.ClaudeResponseParser;
import net.balsoftware.claude.ClaudeResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeResponseParserTest2 {

    @Test
    void testKnownBrokenClaudeResponse() throws Exception {
        // load a real problematic response
        Path p = Path.of("src/test/resources/claude-responses/fail-001.txt");
        String raw = Files.readString(p);

        ClaudeResponseParser parser = new ClaudeResponseParser();
        ClaudeResponse resp = parser.parse(raw);

        // You might want to assert either successful parse,
        // or that a fallback description is provided, etc.
        assertNotNull(resp.description());

        // For debugging, print out what it parsed
        System.out.println("Parsed Claude description: " + resp.description());
        System.out.println("Number of files: " + resp.files().size());
    }
}