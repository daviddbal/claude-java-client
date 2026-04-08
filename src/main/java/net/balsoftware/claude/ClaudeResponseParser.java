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
     * All token counts come from the API envelope (extracted by {@link ClaudeClient}).
     */
    public ClaudeResponse parse(String rawText, int inputTokens, int outputTokens,
                                int cacheCreationTokens, int cacheReadTokens) throws IOException {
        String trimmed = rawText.strip();

        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[a-zA-Z]*\\n?", "");
            trimmed = trimmed.replaceFirst("```\\s*$", "");
            trimmed = trimmed.strip();
        }

        if (trimmed.startsWith("{")) {
            JsonNode root = objectMapper.readTree(trimmed);
            String description = root.path("description").asText("");
            List<GeneratedFile> files = new ArrayList<>();
            for (JsonNode fileNode : root.path("files")) {
                files.add(new GeneratedFile(
                        fileNode.path("path").asText(),
                        fileNode.path("content").asText()
                ));
            }
            return new ClaudeResponse(description, files, inputTokens, outputTokens,
                    cacheCreationTokens, cacheReadTokens);
        } else {
            return new ClaudeResponse(trimmed, List.of(), inputTokens, outputTokens,
                    cacheCreationTokens, cacheReadTokens);
        }
    }

    /** Overload for callers that supply normal token counts only. */
    public ClaudeResponse parse(String rawText, int inputTokens, int outputTokens) throws IOException {
        return parse(rawText, inputTokens, outputTokens, 0, 0);
    }

    /** Convenience overload when token counts are unavailable (e.g. in tests). */
    public ClaudeResponse parse(String rawJson) throws IOException {
        return parse(rawJson, 0, 0, 0, 0);
    }
}