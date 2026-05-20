package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SourceFileCollector {

    /** Cached file content tagged with the last-modified time it was read at. */
    private record CachedFile(long lastModified, SourceFile file) {}

    private final SourceRootConfig sourceRootConfig;
    private final Map<Path, CachedFile> cache = new HashMap<>();

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
                files.add(readCached(resolveSourcePath(clazz)));
            } catch (IOException e) {
                // Log and skip classes whose source files cannot be resolved/read
                System.err.println("[SourceFileCollector] Could not resolve source for " + clazz.getName() + ": " + e.getMessage());
            }
        }
        return files;
    }

    /**
     * Reads a source file, reusing the cached content only while the file is unchanged.
     * This avoids serving stale content if the file is edited during a session.
     */
    private SourceFile readCached(Path path) throws IOException {
        long lastModified = Files.getLastModifiedTime(path).toMillis();
        CachedFile cached = cache.get(path);
        if (cached != null && cached.lastModified() == lastModified) {
            return cached.file();
        }
        SourceFile fresh = new SourceFile(path, Files.readString(path));
        cache.put(path, new CachedFile(lastModified, fresh));
        return fresh;
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
