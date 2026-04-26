package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClaudeResponseParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse a Claude response into a structured ClaudeResponse object.
     */
    public ClaudeResponse parse(String rawText,
                                int inputTokens,
                                int outputTokens,
                                int cacheCreationTokens,
                                int cacheReadTokens) throws IOException {

        if (rawText == null || rawText.isBlank()) {
            throw new IOException("Claude response is empty");
        }

        String trimmed = rawText.strip();

        // Remove code fences if present
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\s*", "");
            trimmed = trimmed.replaceFirst("```\\s*$", "");
            trimmed = trimmed.strip();
        }

        // Extract the first complete JSON block safely
        String jsonCandidate = extractJson(trimmed);

        if (jsonCandidate != null) {
            try {
                JsonNode root = objectMapper.readTree(jsonCandidate);

                String description = root.path("description").asText("");

                List<GeneratedFile> files = new ArrayList<>();
                JsonNode filesNode = root.path("files");

                if (filesNode.isArray()) {
                    for (JsonNode fileNode : filesNode) {
                        files.add(new GeneratedFile(
                                fileNode.path("path").asText(""),
                                fileNode.path("content").asText("")
                        ));
                    }
                }

                return new ClaudeResponse(
                        description,
                        files,
                        inputTokens,
                        outputTokens,
                        cacheCreationTokens,
                        cacheReadTokens
                );

            } catch (IOException e) {
                // JSON exists but is invalid or truncated
                System.err.println("⚠️ Failed to parse JSON from Claude response.");
                System.err.println("---- RAW JSON CANDIDATE (TRUNCATED) ----");
                System.err.println(truncate(jsonCandidate, 2000));
                System.err.println("---- END ----");

                e.printStackTrace();
            }
        }

        // Fallback: treat as plain text response
        return new ClaudeResponse(
                trimmed,
                List.of(),
                inputTokens,
                outputTokens,
                cacheCreationTokens,
                cacheReadTokens
        );
    }

    /**
     * Extracts the first complete JSON object from mixed model output.
     * Balances braces to avoid capturing incomplete JSON fragments.
     */
    private String extractJson(String text) {
        int braceDepth = 0;
        int startIndex = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (braceDepth == 0) {
                    startIndex = i; // potential start of JSON
                }
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0 && startIndex >= 0) {
                    // found a complete JSON object
                    return text.substring(startIndex, i + 1);
                }
            }
        }

        // No valid JSON found
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    // Overloads for convenience

    public ClaudeResponse parse(String rawText, int inputTokens, int outputTokens) throws IOException {
        return parse(rawText, inputTokens, outputTokens, 0, 0);
    }

    public ClaudeResponse parse(String rawJson) throws IOException {
        return parse(rawJson, 0, 0, 0, 0);
    }
}