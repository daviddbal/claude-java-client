package net.balsoftware.claude;

import java.util.Scanner;

public class MultiLineInputReader implements InputReader {

    private static final String END_MARKER = "<<<END";

    @Override
    public String read(Scanner scanner) {

        if (!scanner.hasNextLine()) {
            return "";
        }

        String firstLine = scanner.nextLine();

        // ----------------------------
        // SINGLE LINE MODE
        // ----------------------------
        if (!firstLine.startsWith("::")) {
            return firstLine;
        }

        // ----------------------------
        // MULTI-LINE MODE
        // ----------------------------
        StringBuilder sb = new StringBuilder();

        String rest = firstLine.substring(2);

        // 🔥 FIX: handle immediate termination
        if (rest.contains(END_MARKER)) {
            String before = rest.substring(0, rest.indexOf(END_MARKER)).stripTrailing();
            return before; // early exit, nothing else is read
        }

        if (!rest.isBlank()) {
            sb.append(rest);
        }

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();

            int markerIndex = line.indexOf(END_MARKER);

            if (markerIndex != -1) {
                String before = line.substring(0, markerIndex).stripTrailing();
                if (!before.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(before);
                }
                break;
            }

            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }

        return sb.toString();
    }
}