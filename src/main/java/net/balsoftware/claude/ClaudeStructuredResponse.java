package net.balsoftware.claude;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaudeStructuredResponse(

        Type type,
        String description,
        List<FileItem> files

) {

    public boolean hasFiles() {
        return files != null && !files.isEmpty();
    }

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

    public String content() {
        if (files == null || files.isEmpty()) return null;

        // If only one file → return its content (most common case in your tests)
        if (files.size() == 1) {
            return files.get(0).content();
        }

        // If multiple files → join them
        return files.stream()
                .map(ClaudeStructuredResponse.FileItem::content)
                .reduce((a, b) -> a + "\n" + b)
                .orElse(null);
    }

    // ---------------- VALIDATION ----------------

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
                // explanation is now ONLY in description or single file
                if ((r.description() == null || r.description().isBlank())
                        && (r.files() == null || r.files().isEmpty())) {
                    throw new IllegalStateException("Explanation missing content");
                }
            }

            case code_with_explanation -> {
                if (r.files() == null || r.files().isEmpty()) {
                    throw new IllegalStateException("Missing files");
                }
            }
        }
    }
}