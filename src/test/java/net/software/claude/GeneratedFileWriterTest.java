package net.balsoftware.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedFileWriterTest {

    private final GeneratedFileWriter writer = new GeneratedFileWriter();

    @Test
    void writesFileToCorrectPath(@TempDir Path tempDir) throws IOException {
        ClaudeResponse response = new ClaudeResponse("ok",
                List.of(new GeneratedFile("com/example/Foo.java", "class Foo {}")), 0, 0);

        writer.writeAll(tempDir, response);

        Path written = tempDir.resolve("com/example/Foo.java");
        assertTrue(Files.exists(written));
        assertEquals("class Foo {}", Files.readString(written));
    }

    @Test
    void createsIntermediateDirectories(@TempDir Path tempDir) throws IOException {
        ClaudeResponse response = new ClaudeResponse("ok",
                List.of(new GeneratedFile("a/b/c/D.java", "class D {}")), 0, 0);

        writer.writeAll(tempDir, response);

        assertTrue(Files.exists(tempDir.resolve("a/b/c/D.java")));
    }

    @Test
    void writesMultipleFiles(@TempDir Path tempDir) throws IOException {
        ClaudeResponse response = new ClaudeResponse("ok", List.of(
                new GeneratedFile("A.java", "class A {}"),
                new GeneratedFile("B.java", "class B {}")
        ), 0, 0);

        writer.writeAll(tempDir, response);

        assertTrue(Files.exists(tempDir.resolve("A.java")));
        assertTrue(Files.exists(tempDir.resolve("B.java")));
    }
}