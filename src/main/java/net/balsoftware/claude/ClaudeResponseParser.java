package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClaudeResponseParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        // Try to extract JSON block safely (more robust than startsWith)
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
     * Tries to extract a JSON object from mixed model output.
     * This is far more reliable than "startsWith({)".
     */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    // Overloads unchanged

    public ClaudeResponse parse(String rawText, int inputTokens, int outputTokens) throws IOException {
        return parse(rawText, inputTokens, outputTokens, 0, 0);
    }

    public ClaudeResponse parse(String rawJson) throws IOException {
        return parse(rawJson, 0, 0, 0, 0);
    }
}