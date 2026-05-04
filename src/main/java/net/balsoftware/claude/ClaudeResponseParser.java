package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ClaudeResponseParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

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

                // ---------------- TYPE ----------------
                ClaudeStructuredResponse.Type type = root.has("type")
                        ? ClaudeStructuredResponse.Type.valueOf(root.get("type").textValue())
                        : ClaudeStructuredResponse.Type.explanation;

                // ---------------- DESCRIPTION ----------------
                String description = Objects.requireNonNullElse(root.path("description").textValue(), "");

                // ---------------- FILES ----------------
                List<ClaudeStructuredResponse.FileItem> files = new ArrayList<>();
                JsonNode filesNode = root.path("files");
                if (filesNode.isArray()) {
                    for (JsonNode f : filesNode) {
                        files.add(new ClaudeStructuredResponse.FileItem(
                                f.path("path").textValue(),
                                f.path("content").textValue()
                        ));
                    }
                }

                ClaudeStructuredResponse response = new ClaudeStructuredResponse(
                        type,
                        description,
                        files
                );

                // validate
                ClaudeStructuredResponse.validate(response);

                return response;

            } catch (Exception e) {
                System.err.println("⚠️ Failed to parse JSON from Claude response:");
                System.err.println(truncate(jsonCandidate, 2000));
                throw e; // <-- propagate the exception to the test
            }
        }

        // ---------------- FALLBACK ----------------
        // Treat raw output as explanation-only metadata
        return new ClaudeStructuredResponse(
                ClaudeStructuredResponse.Type.explanation,
                trimmed,
                List.of()
        );
    }

    // ---------------- JSON EXTRACTION ----------------
    private String extractJson(String text) {
        int depth = 0;
        int start = -1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    return text.substring(start, i + 1);
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