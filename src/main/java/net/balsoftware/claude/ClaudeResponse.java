package net.balsoftware.claude;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public record ClaudeResponse(
        String description,
        List<GeneratedFile> files,
        int inputTokens,
        int outputTokens
) {
    /** Returns true when Claude generated at least one file. */
    public boolean hasFiles() {
        return !files.isEmpty();
    }

    /** Returns the first generated file, or empty. */
    public Optional<GeneratedFile> firstFile() {
        return files.stream().findFirst();
    }

    /** Returns a map of path → content for easy lookup. */
    public Map<String, String> filesByPath() {
        return files.stream()
                .collect(Collectors.toMap(GeneratedFile::path, GeneratedFile::content));
    }
}