package net.balsoftware.claude;

import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiLineInputReaderTest {

    @Test
    void testSingleLineInput() {
        String simulatedInput = "This is a single line\n"; // simulate Enter once
        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = MultiLineInputReader.readMultiLineInput(scanner);

        assertEquals("This is a single line", result);
    }

    @Test
    void testMultiLineInput() {
        String simulatedInput = "Line 1\nLine 2\nLine 3\n"; // multiple lines
        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = MultiLineInputReader.readMultiLineInput(scanner);

        String expected = "Line 1\nLine 2\nLine 3";
        assertEquals(expected, result);
    }

    @Test
    void testInputEndingWithoutBlankLines() {
        String simulatedInput = "Only line with no trailing blank lines";
        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = MultiLineInputReader.readMultiLineInput(scanner);

        assertEquals("Only line with no trailing blank lines", result);
    }

    @Test
    void testCommandRecognition() {
        String simulatedInput = "reset\n";
        Scanner scanner = new Scanner(new StringReader(simulatedInput));

        String result = MultiLineInputReader.readMultiLineInput(scanner);

        // Command detection is separate; reader just reads text
        assertEquals("reset", result);
        assertEquals(true, MultiLineInputReader.isCommand(result));
    }
}