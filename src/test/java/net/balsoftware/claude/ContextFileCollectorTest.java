package net.balsoftware.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextFileCollectorTest {

    @Test
    void returnsEmptyListWhenDirectoryDoesNotExist(@TempDir Path tempDir) throws IOException {
        ContextFileCollector collector = new ContextFileCollector(tempDir.resolve("nonexistent"));
        assertTrue(collector.collect().isEmpty());
    }

    @Test
    void collectsSingleFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("notes.md"), "# Notes\nSome context here.");
        ContextFileCollector collector = new ContextFileCollector(tempDir);

        List<SourceFile> files = collector.collect();
        assertEquals(1, files.size());
        assertEquals("# Notes\nSome context here.", files.get(0).content());
    }

    @Test
    void collectsMultipleFileTypesRecursively(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("schema.sql"), "CREATE TABLE foo (id INT);");
        Path sub = Files.createDirectory(tempDir.resolve("docs"));
        Files.writeString(sub.resolve("api.md"), "# API docs");
        Files.writeString(sub.resolve("config.yaml"), "key: value");

        List<SourceFile> files = new ContextFileCollector(tempDir).collect();
        assertEquals(3, files.size());
    }

    @Test
    void returnsEmptyListForEmptyDirectory(@TempDir Path tempDir) throws IOException {
        assertTrue(new ContextFileCollector(tempDir).collect().isEmpty());
    }

    @Test
    void filesAreSortedDeterministically(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("c.txt"), "c");
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");

        List<SourceFile> files = new ContextFileCollector(tempDir).collect();
        assertEquals("a", files.get(0).content());
        assertEquals("b", files.get(1).content());
        assertEquals("c", files.get(2).content());
    }
}