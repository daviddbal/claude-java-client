package net.balsoftware.claude;

import java.nio.file.Path;
import java.util.List;

public record SourceRootConfig(
        List<Path> roots
) {}