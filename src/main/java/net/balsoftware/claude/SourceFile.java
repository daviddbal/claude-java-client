package net.balsoftware.claude;

import java.nio.file.Path;

public record SourceFile(
        Path path,
        String content
) {}