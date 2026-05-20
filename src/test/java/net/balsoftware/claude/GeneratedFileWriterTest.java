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

    private static ClaudeStructuredResponseWithTokens responseWithFiles(
            List<ClaudeStructuredResponse.FileItem> files
    ) {
        ClaudeStructuredResponse response = new ClaudeStructuredResponse(
                ClaudeStructuredResponse.Type.code,
                "desc",
                files
        );

        return new ClaudeStructuredResponseWithTokens(response, 1, 1, 1, 1);
    }

    @Test
    void writesFileToCorrectPath(@TempDir Path tempDir) throws IOException {

        var file = new ClaudeStructuredResponse.FileItem(
                "com/example/Foo.java",
                "class Foo {}"
        );

        var response = responseWithFiles(List.of(file));

        writer.writeAll(tempDir, response);

        Path written = tempDir.resolve("com/example/Foo.java");

        assertTrue(Files.exists(written));
        assertEquals("class Foo {}", Files.readString(written));
    }

    @Test
    void createsIntermediateDirectories(@TempDir Path tempDir) throws IOException {

        var file = new ClaudeStructuredResponse.FileItem(
                "a/b/c/D.java",
                "class D {}"
        );

        var response = responseWithFiles(List.of(file));

        writer.writeAll(tempDir, response);

        assertTrue(Files.exists(tempDir.resolve("a/b/c/D.java")));
    }

    @Test
    void rejectsPathTraversalEscape(@TempDir Path tempDir) {
        var file = new ClaudeStructuredResponse.FileItem(
                "../escaped.java",
                "class Escaped {}"
        );

        IOException ex = assertThrows(IOException.class,
                () -> writer.writeAll(tempDir, responseWithFiles(List.of(file))));
        assertTrue(ex.getMessage().contains("outside output root"));

        assertFalse(Files.exists(tempDir.getParent().resolve("escaped.java")),
                "must not write outside the output root");
    }

    @Test
    void allowsRelativePathThatStaysInsideRoot(@TempDir Path tempDir) throws IOException {
        // "a/../b/C.java" normalizes to "b/C.java", still inside the root → allowed.
        var file = new ClaudeStructuredResponse.FileItem(
                "a/../b/C.java",
                "class C {}"
        );

        writer.writeAll(tempDir, responseWithFiles(List.of(file)));

        assertTrue(Files.exists(tempDir.resolve("b/C.java")));
        assertEquals("class C {}", Files.readString(tempDir.resolve("b/C.java")));
    }

    @Test
    void rejectsAbsolutePathEscape(@TempDir Path tempDir) {
        Path outside = tempDir.getParent().resolve("absolute-escape.java");
        var file = new ClaudeStructuredResponse.FileItem(
                outside.toString(),
                "class Absolute {}"
        );

        assertThrows(IOException.class,
                () -> writer.writeAll(tempDir, responseWithFiles(List.of(file))));
        assertFalse(Files.exists(outside), "must not write to an absolute path outside the root");
    }

    @Test
    void writesMultipleFiles(@TempDir Path tempDir) throws IOException {

        var files = List.of(
                new ClaudeStructuredResponse.FileItem("A.java", "class A {}"),
                new ClaudeStructuredResponse.FileItem("B.java", "class B {}")
        );

        var response = responseWithFiles(files);

        writer.writeAll(tempDir, response);

        assertTrue(Files.exists(tempDir.resolve("A.java")));
        assertTrue(Files.exists(tempDir.resolve("B.java")));

        assertEquals("class A {}",
                Files.readString(tempDir.resolve("A.java")));

        assertEquals("class B {}",
                Files.readString(tempDir.resolve("B.java")));
    }
}