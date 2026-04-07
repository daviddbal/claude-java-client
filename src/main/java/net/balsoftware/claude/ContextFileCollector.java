package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Collects all files from a directory tree and returns them as {@link SourceFile}s
 * so they can be injected into the Claude system prompt as additional context.
 *
 * <p>Place any file type (Markdown, SQL, YAML, plain text, Java, etc.) inside the
 * configured directory and it will automatically be included in every request.
 *
 * <p>The directory is conventionally named {@code context-files/} and lives at
 * the same level as the {@code generated/} output directory.
 */
public class ContextFileCollector {

    private final Path contextRoot;

    public ContextFileCollector(Path contextRoot) {
        this.contextRoot = contextRoot;
    }

    /**
     * Returns all readable files under {@link #contextRoot}, recursively.
     * Returns an empty list if the directory does not exist.
     */
    public List<SourceFile> collect() throws IOException {
        if (!Files.isDirectory(contextRoot)) {
            return List.of();
        }

        List<SourceFile> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(contextRoot)) {
            walk.filter(Files::isRegularFile)
                    .sorted()                          // deterministic order
                    .forEach(p -> {
                        try {
                            result.add(new SourceFile(p, Files.readString(p)));
                        } catch (IOException e) {
                            // Skip unreadable files (binary, permissions, etc.) silently
                            System.err.println("[ContextFileCollector] Skipping unreadable file: " + p + " — " + e.getMessage());
                        }
                    });
        }
        return result;
    }

    public Path getContextRoot() {
        return contextRoot;
    }
}