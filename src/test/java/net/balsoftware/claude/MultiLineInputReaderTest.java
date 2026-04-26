package net.balsoftware.claude;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.*;

class MultiLineInputReaderTest {

    private final MultiLineInputReader reader = new MultiLineInputReader();

    @Test
    void testSingleLineInput() {
        String simulatedInput = "hello world\n";
        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = reader.read(scanner);

        assertEquals("hello world", result);
    }

    @Test
    void testMultiLineInputWithEndMarker() {
        String simulatedInput =
                "::Line 1\n" +
                        "Line 2\n" +
                        "Line 3 <<<END\n" +
                        "ignored\n";

        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = reader.read(scanner);

        assertEquals("Line 1\nLine 2\nLine 3", result);
    }

    @Test
    void testEndMarkerMidLine() {
        String simulatedInput =
                "::Hello\n" +
                        "world<<<END\n" +
                        "ignored\n";

        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = reader.read(scanner);

        assertEquals("Hello\nworld", result);
    }

    @Test
    void testEndMarkerOnlyLine() {
        String simulatedInput =
                "::Line 1\n" +
                        "<<<END\n";

        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = reader.read(scanner);

        assertEquals("Line 1", result);
    }

    @Test
    void testInlineContentAfterPrefix() {
        String simulatedInput =
                "::Line 1 inline\n" +
                        "Line 2\n" +
                        "<<<END\n";

        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = reader.read(scanner);

        assertEquals("Line 1 inline\nLine 2", result);
    }

    @Test
    void testEmptyMultiLineBlock() {
        String simulatedInput =
                "::<<<END\n";

        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = reader.read(scanner);

        assertEquals("", result);
    }

    @Test
    void testCommandRecognition() {
        assertTrue(MultiLineInputReader.isCommand("reset"));
        assertTrue(MultiLineInputReader.isCommand("write"));
        assertFalse(MultiLineInputReader.isCommand("hello world"));
    }
}