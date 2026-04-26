package net.balsoftware.claude;

import java.util.Scanner;

public class MultiLineInputReader {

    /**
     * Reads multi-line input from a Scanner.
     * Stops when EOF is reached (Ctrl-D on Unix/macOS, Ctrl-Z on Windows).
     * Preserves blank lines inside the content.
     * Users must press Enter after each line.
     */
    public static String readMultiLineInput(Scanner scanner) {
        StringBuilder sb = new StringBuilder();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();

            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }

        return sb.toString();
    }

    /**
     * Checks if the input is a recognized command.
     */
    public static boolean isCommand(String input) {
        if (input == null) return false;
        switch (input.toLowerCase().trim()) {
            case "quit", "reset", "write", "show", "turns", "tokens", "context":
                return true;
            default:
                return false;
        }
    }
}