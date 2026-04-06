package net.balsoftware.claude;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceFileCollector {
    private final SourceRootConfig sourceRootConfig;
    private final Map<Path, SourceFile> cache = new HashMap<>();

    public SourceFileCollector(SourceRootConfig sourceRootConfig) {
        this.sourceRootConfig = sourceRootConfig;
    }

    public List<SourceFile> collect(List<Class<?>> classes) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        for (Class<?> clazz : classes) {
            Path sourcePath = resolveSourcePath(clazz);
            files.add(cache.computeIfAbsent(sourcePath, p -> {
                try {
                    return new SourceFile(p, Files.readString(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }));
        }
        return files;
    }

    private Path resolveSourcePath(Class<?> clazz) throws IOException {
        String className = clazz.getName();
        String topLevelName = stripNestedClassSuffix(className);
        String relativePath = topLevelName.replace('.', '/') + ".java";

        for (Path root : sourceRootConfig.roots()) {
            Path candidate = root.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IOException("Could not find source file for class: " + clazz.getName());
    }

    private String stripNestedClassSuffix(String className) {
        int index = className.indexOf('$');
        return index >= 0 ? className.substring(0, index) : className;
    }
}