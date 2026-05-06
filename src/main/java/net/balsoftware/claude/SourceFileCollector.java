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
            // Skip JDK and library classes that don't have source files
            if (isSystemClass(clazz)) {
                continue;
            }

            try {
                Path sourcePath = resolveSourcePath(clazz);
                files.add(cache.computeIfAbsent(sourcePath, p -> {
                    try {
                        return new SourceFile(p, Files.readString(p));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            } catch (IOException e) {
                // Log and skip classes whose source files cannot be resolved
                System.err.println("[SourceFileCollector] Could not resolve source for " + clazz.getName() + ": " + e.getMessage());
            }
        }
        return files;
    }

    /**
     * Determines if a class is from JDK or standard library (should be skipped).
     */
    private boolean isSystemClass(Class<?> clazz) {
        String name = clazz.getName();
        String classLoader = clazz.getClassLoader() != null ? clazz.getClassLoader().getClass().getName() : "null";

        // Check package name prefixes for JDK/standard library
        return name.startsWith("java.") ||
               name.startsWith("javax.") ||
               name.startsWith("sun.") ||
               name.startsWith("jdk.") ||
               name.startsWith("com.sun.") ||
               classLoader.contains("PlatformClassLoader") ||
               classLoader.contains("AppClassLoader") && isBootstrapClass(name);
    }

    /**
     * Additional check for bootstrap classes loaded by system classloader.
     */
    private boolean isBootstrapClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return clazz.getProtectionDomain().getCodeSource() == null ||
                   clazz.getProtectionDomain().getCodeSource().getLocation() == null ||
                   clazz.getProtectionDomain().getCodeSource().getLocation().getPath().contains("jrt-fs.jar") ||
                   clazz.getProtectionDomain().getCodeSource().getLocation().getPath().contains("/lib/");
        } catch (ClassNotFoundException e) {
            return false;
        }
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
