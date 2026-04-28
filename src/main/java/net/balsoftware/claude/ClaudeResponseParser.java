package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClaudeResponseParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse a Claude response into a structured ClaudeStructuredResponse object.
     */
    public ClaudeStructuredResponse parseStructured(String rawText) throws IOException {

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

        String jsonCandidate = extractJson(trimmed);

        if (jsonCandidate != null) {
            try {
                JsonNode root = objectMapper.readTree(jsonCandidate);

                ClaudeStructuredResponse.Type type = root.has("type")
                        ? ClaudeStructuredResponse.Type.valueOf(root.get("type").asText())
                        : null;

                String description = root.path("description").asText(null);
                String content = root.path("content").asText(null);
                String explanation = root.path("explanation").asText(null);

                List<ClaudeStructuredResponse.FileItem> files = new ArrayList<>();
                JsonNode filesNode = root.path("files");
                if (filesNode.isArray()) {
                    for (JsonNode f : filesNode) {
                        files.add(new ClaudeStructuredResponse.FileItem(
                                f.path("path").asText(null),
                                f.path("content").asText(null)
                        ));
                    }
                }

                ClaudeStructuredResponse response = new ClaudeStructuredResponse(
                        type, description, content, explanation, files
                );

                // Validate required fields for the type
                ClaudeStructuredResponse.validate(response);

                return response;

            } catch (Exception e) {
                System.err.println("⚠️ Failed to parse JSON from Claude response:");
                System.err.println(truncate(jsonCandidate, 2000));
                e.printStackTrace();
            }
        }

        // Fallback: treat as explanation-only plain text
        return new ClaudeStructuredResponse(
                ClaudeStructuredResponse.Type.explanation,
                null,
                trimmed,
                null,
                List.of()
        );
    }

    /**
     * Extracts the first complete JSON object from mixed model output.
     */
    private String extractJson(String text) {
        int braceDepth = 0;
        int startIndex = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (braceDepth == 0) startIndex = i;
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0 && startIndex >= 0) {
                    return text.substring(startIndex, i + 1);
                }
            }
        }
        return null;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}