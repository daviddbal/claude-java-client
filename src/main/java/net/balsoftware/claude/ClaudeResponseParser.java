package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClaudeResponseParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parses the text content returned by Claude into a {@link ClaudeResponse}.
     * Token counts come from the API envelope (already extracted by {@link ClaudeClient}).
     */
    public ClaudeResponse parse(String rawText, int inputTokens, int outputTokens) throws IOException {
        String trimmed = rawText.strip();

        // Strip markdown code fences Claude sometimes wraps responses in
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\n?", "");
            trimmed = trimmed.replaceFirst("```\\s*$", "");
            trimmed = trimmed.strip();
        }

        if (trimmed.startsWith("{")) {
            // Structured code-generation response
            JsonNode root = objectMapper.readTree(trimmed);
            String description = root.path("description").asText("");
            List<GeneratedFile> files = new ArrayList<>();
            for (JsonNode fileNode : root.path("files")) {
                files.add(new GeneratedFile(
                        fileNode.path("path").asText(),
                        fileNode.path("content").asText()
                ));
            }
            return new ClaudeResponse(description, files, inputTokens, outputTokens);
        } else {
            // Plain conversational response — no files
            return new ClaudeResponse(trimmed, List.of(), inputTokens, outputTokens);
        }
    }

    /** Convenience overload when token counts are unavailable (e.g. in tests). */
    public ClaudeResponse parse(String rawJson) throws IOException {
        return parse(rawJson, 0, 0);
    }
}