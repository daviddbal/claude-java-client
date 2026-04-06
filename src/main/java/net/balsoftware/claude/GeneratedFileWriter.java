package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GeneratedFileWriter {

    public void writeAll(Path outputRoot, ClaudeResponse response) throws IOException {
        for (GeneratedFile file : response.files()) {
            Path target = outputRoot.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content());
        }
    }
}