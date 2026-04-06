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
    public ClaudeResponse parse(String rawJson, int inputTokens, int outputTokens) throws IOException {
        JsonNode root = objectMapper.readTree(rawJson);

        String description = root.path("description").asText("");
        List<GeneratedFile> files = new ArrayList<>();

        for (JsonNode fileNode : root.path("files")) {
            String path    = fileNode.path("path").asText();
            String content = fileNode.path("content").asText();
            files.add(new GeneratedFile(path, content));
        }

        return new ClaudeResponse(description, files, inputTokens, outputTokens);
    }

    /** Convenience overload when token counts are unavailable (e.g. in tests). */
    public ClaudeResponse parse(String rawJson) throws IOException {
        return parse(rawJson, 0, 0);
    }
}