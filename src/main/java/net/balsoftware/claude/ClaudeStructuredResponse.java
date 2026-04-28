package net.balsoftware.claude;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaudeStructuredResponse(

        Type type,

        String description,     // for code or code_with_explanation
        String content,         // for explanation-only
        String explanation,     // for code_with_explanation

        List<FileItem> files

) {

    public enum Type {
        code,
        explanation,
        code_with_explanation
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileItem(
            String path,
            String content
    ) {}

    // ----------- Convenience helpers -----------

    public boolean isCode() {
        return type == Type.code;
    }

    public boolean isExplanation() {
        return type == Type.explanation;
    }

    public boolean isCodeWithExplanation() {
        return type == Type.code_with_explanation;
    }

    public boolean hasFiles() {
        return files != null && !files.isEmpty();
    }

    public static void validate(ClaudeStructuredResponse r) {
        if (r.type() == null) {
            throw new IllegalStateException("Missing type");
        }

        switch (r.type()) {
            case code -> {
                if (r.files() == null || r.files().isEmpty()) {
                    throw new IllegalStateException("Code response missing files");
                }
            }
            case explanation -> {
                if (r.content() == null || r.content().isBlank()) {
                    throw new IllegalStateException("Explanation missing content");
                }
            }
            case code_with_explanation -> {
                if (r.files() == null || r.files().isEmpty()) {
                    throw new IllegalStateException("Missing files");
                }
                if (r.explanation() == null || r.explanation().isBlank()) {
                    throw new IllegalStateException("Missing explanation");
                }
            }
        }
    }


    // ----------- System prompt builder -----------

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static String buildSystemPrompt() {
        try {
            // Build example responses for all three types
            List<ClaudeStructuredResponse> examples = List.of(
                    new ClaudeStructuredResponse(
                            Type.code,
                            "string (optional)",
                            "string (for code)",
                            null,
                            List.of(new FileItem("string", "string"))
                    ),
                    new ClaudeStructuredResponse(
                            Type.explanation,
                            "string (optional)",
                            null,
                            "string (for explanation)",
                            List.of()
                    ),
                    new ClaudeStructuredResponse(
                            Type.code_with_explanation,
                            "string (optional)",
                            "string (for code_with_explanation)",
                            "string (for explanation)",
                            List.of(new FileItem("string", "string"))
                    )
            );

            String examplesJson = OBJECT_MAPPER.writeValueAsString(examples);

            return """
                You are a senior Java software engineer assistant.

                You MUST ALWAYS return a valid JSON object matching one of these structures:

                %s

                Rules:
                - Do NOT include markdown or code fences
                - Do NOT include any text outside the JSON
                - Choose the correct "type" based on the request
                """.formatted(examplesJson);

        } catch (Exception e) {
            throw new RuntimeException("Failed to build system prompt", e);
        }
    }
}