package net.balsoftware.claude;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClaudeResponseParser {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
            .configure(JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, true);

    public ClaudeStructuredResponse parseStructured(String rawText) throws IOException {
        String cleaned = sanitize(rawText);
        String jsonCandidate = extractJson(cleaned);

        if (jsonCandidate == null) {
            return fallback(cleaned);
        }

        try {
            // Attempt standard parse first
            return parseJson(jsonCandidate);
        } catch (IllegalStateException e) {
            // Validation error — wrap and propagate
            throw new IOException("Failed to parse LLM JSON response: " + e.getMessage(), e);
        } catch (Exception e) {
            // If standard parse fails, try the robust unescape path
            try {
                String unescapedCandidate = robustUnescape(jsonCandidate);
                return parseJson(unescapedCandidate);
            } catch (IllegalStateException validationError) {
                // Validation failed after unescape
                throw new IOException("Failed to parse LLM JSON response: " + validationError.getMessage(), validationError);
            } catch (Exception ex) {
                System.err.println("--- PARSE ERROR ---");
                System.err.println("Original: " + jsonCandidate);
                throw new IOException("Failed to parse LLM JSON response after robust unescape", ex);
            }
        }
    }

    private ClaudeStructuredResponse parseJson(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        ClaudeStructuredResponse.Type type = root.has("type")
                ? ClaudeStructuredResponse.Type.valueOf(root.get("type").asText())
                : ClaudeStructuredResponse.Type.explanation;

        String desc = root.path("description").asText("");
        List<ClaudeStructuredResponse.FileItem> files = new ArrayList<>();

        JsonNode filesNode = root.path("files");
        if (filesNode.isArray()) {
            for (JsonNode f : filesNode) {
                files.add(new ClaudeStructuredResponse.FileItem(
                        f.path("path").asText(),
                        f.path("content").asText()
                ));
            }
        }

        ClaudeStructuredResponse response = new ClaudeStructuredResponse(type, desc, files);
        ClaudeStructuredResponse.validate(response);
        return response;
    }

    // Removes markdown backticks/quotes and leading/trailing whitespace.
    private String sanitize(String input) {
        if (input == null) return "";
        // Remove markdown code fences
        String result = input.replaceAll("(?s)^(`{3,}|'{3,})\\w*\\s*", "");
        result = result.replaceAll("(`{3,}|'{3,})\\s*$", "");
        return result.trim();
    }

    // Improved JSON extraction: finds the first balanced JSON object
    private String extractJson(String text) {
        int start = text.indexOf('{');
        if (start == -1) return null;

        // Find the balanced closing brace
        int end = findMatchingClosingBrace(text, start);
        if (end == -1) return null;

        return text.substring(start, end + 1).trim();
    }

    private int findMatchingClosingBrace(String text, int start) {
        int count = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') count++;
            else if (c == '}') count--;

            if (count == 0) return i;
        }
        return -1;
    }
    // Robust unescaping: only triggers if standard parsing fails.
    private String robustUnescape(String s) {
        String candidate = s.trim();
        // Remove outer quotes if the entire block was wrapped in a string
        if (candidate.startsWith("\"") && candidate.endsWith("\"") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        // Unescape Java/JSON escaping sequences
        return StringEscapeUtils.unescapeJava(candidate);
    }

    private ClaudeStructuredResponse fallback(String text) {
        return new ClaudeStructuredResponse(
                ClaudeStructuredResponse.Type.explanation,
                text,
                List.of()
        );
    }
}
