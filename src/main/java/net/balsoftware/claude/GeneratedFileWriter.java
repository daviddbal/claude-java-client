package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class GeneratedFileWriter {

    // Write all files from a ClaudeStructuredResponse
    public void writeAll(Path outputRoot, ClaudeStructuredResponse response) throws IOException {
        if (response == null || response.files() == null || response.files().isEmpty()) return;

        Path root = outputRoot.toAbsolutePath().normalize();

        for (ClaudeStructuredResponse.FileItem file : response.files()) {
            // The path comes from the model; resolve and normalize it, then verify it stays
            // inside the output root so a "../" or absolute path can't escape and overwrite
            // arbitrary files.
            Path target = root.resolve(file.path()).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("Refusing to write outside output root: " + file.path());
            }

            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, file.content() != null ? file.content() : "");
        }
    }

    // Write all files from a ClaudeStructuredResponseWithTokens
    public void writeAll(Path outputRoot, ClaudeStructuredResponseWithTokens response) throws IOException {
        if (response == null) return;
        writeAll(outputRoot, response.structured());
    }
}