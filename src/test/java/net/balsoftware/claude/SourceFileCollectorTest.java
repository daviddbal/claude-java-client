package net.balsoftware.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceFileCollectorTest {

    // Uses a real project class as the probe: the collector derives the source path from the
    // class name (net/balsoftware/claude/ClaudeModel.java) under the configured root.
    private static Path layoutProbeSource(Path root, String content) throws IOException {
        Path src = root.resolve("net/balsoftware/claude/ClaudeModel.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, content);
        return src;
    }

    @Test
    void reReadsSourceFileAfterItChanges(@TempDir Path root) throws IOException {
        Path src = layoutProbeSource(root, "// version 1");
        SourceFileCollector collector = new SourceFileCollector(new SourceRootConfig(List.of(root)));

        List<SourceFile> first = collector.collect(List.of(ClaudeModel.class));
        assertEquals(1, first.size(), "probe class should resolve to the laid-out source file");
        assertEquals("// version 1", first.get(0).content());

        long firstMtime = Files.getLastModifiedTime(src).toMillis();

        // Change the file and force a strictly later modified time (coarse-grained filesystems
        // could otherwise reuse the same millisecond).
        Files.writeString(src, "// version 2");
        Files.setLastModifiedTime(src, FileTime.fromMillis(firstMtime + 10_000));

        List<SourceFile> second = collector.collect(List.of(ClaudeModel.class));
        assertEquals("// version 2", second.get(0).content(),
                "collector should re-read the file after its last-modified time changes");
    }

    @Test
    void reusesCacheWhenLastModifiedUnchanged(@TempDir Path root) throws IOException {
        Path src = layoutProbeSource(root, "// original");
        FileTime fixed = Files.getLastModifiedTime(src);

        SourceFileCollector collector = new SourceFileCollector(new SourceRootConfig(List.of(root)));
        collector.collect(List.of(ClaudeModel.class)); // primes the cache

        // Change the content but restore the original last-modified time → the collector should
        // treat the file as unchanged and serve the cached content.
        Files.writeString(src, "// changed but same mtime");
        Files.setLastModifiedTime(src, fixed);

        List<SourceFile> again = collector.collect(List.of(ClaudeModel.class));
        assertEquals("// original", again.get(0).content(),
                "unchanged last-modified time should serve the cached content");
    }
}
