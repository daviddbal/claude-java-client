package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class GeneratedFileWriter {

    // Write all files from a ClaudeStructuredResponse
    public void writeAll(Path outputRoot, ClaudeStructuredResponse response) throws IOException {
        if (response == null || response.files() == null || response.files().isEmpty()) return;

        for (ClaudeStructuredResponse.FileItem file : response.files()) {
            Path target = outputRoot.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content() != null ? file.content() : "");
        }
    }

    // Write all files from a ClaudeStructuredResponseWithTokens
    public void writeAll(Path outputRoot, ClaudeStructuredResponseWithTokens response) throws IOException {
        if (response == null) return;
        writeAll(outputRoot, response.structured());
    }
}