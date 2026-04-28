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

    private static ClaudeStructuredResponseWithTokens responseWithFiles(List<ClaudeStructuredResponse.FileItem> files) {
        var response = new ClaudeStructuredResponse(
                ClaudeStructuredResponse.Type.code, // type doesn't matter here
                "desc", "content", null,
                files
        );
        // Token counts arbitrary for file writing
        return new ClaudeStructuredResponseWithTokens(response, 1, 1, 1, 1);
    }

    @Test
    void writesFileToCorrectPath(@TempDir Path tempDir) throws IOException {
        var file = new ClaudeStructuredResponse.FileItem("com/example/Foo.java", "class Foo {}");
        var response = responseWithFiles(List.of(file));
        writer.writeAll(tempDir, response);

        Path written = tempDir.resolve("com/example/Foo.java");
        assertTrue(Files.exists(written));
        assertEquals("class Foo {}", Files.readString(written));
    }

    @Test
    void createsIntermediateDirectories(@TempDir Path tempDir) throws IOException {
        var file = new ClaudeStructuredResponse.FileItem("a/b/c/D.java", "class D {}");
        var response = responseWithFiles(List.of(file));

        writer.writeAll(tempDir, response);

        assertTrue(Files.exists(tempDir.resolve("a/b/c/D.java")));
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
    }
}