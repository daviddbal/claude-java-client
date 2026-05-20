package net.balsoftware.claude;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Incrementally extracts the human-readable {@code description} field from a structured
 * response as its JSON streams in, forwarding the decoded prose to a delegate consumer.
 *
 * <p>It is a {@link Consumer Consumer&lt;String&gt;} of raw text chunks (as produced by
 * {@link ClaudeClient#sendStreaming}), so it can be dropped straight into
 * {@link ClaudeSession#askStreaming}. It tracks just enough JSON structure to identify the
 * top-level {@code "description"} string value and streams its characters (JSON escapes
 * decoded) the moment they arrive — chunk boundaries, including those splitting a
 * {@code \\uXXXX} escape, are handled.
 *
 * <p>Only the root-object {@code description} is streamed; other fields (e.g. file
 * {@code content}, which is code rather than prose) are ignored. Leading/trailing noise
 * outside the JSON object (such as a Markdown code fence) is ignored.
 */
public final class StreamingProseExtractor implements Consumer<String> {

    private final Consumer<String> prose;

    // Structural state.
    private final Deque<Character> stack = new ArrayDeque<>();
    private boolean expectingKey = false;
    private String lastKey = null;

    // String-scanning state.
    private boolean inString = false;
    private boolean escape = false;
    private boolean currentIsKey = false;
    private boolean streamingCurrentValue = false;
    private final StringBuilder currentString = new StringBuilder();

    // Unicode escape accumulation (may span chunk boundaries).
    private int unicodeRemaining = 0;
    private final StringBuilder unicodeBuf = new StringBuilder();

    public StreamingProseExtractor(Consumer<String> prose) {
        this.prose = prose;
    }

    @Override
    public void accept(String rawChunk) {
        if (rawChunk == null) return;
        for (int i = 0; i < rawChunk.length(); i++) {
            feed(rawChunk.charAt(i));
        }
    }

    private void feed(char c) {
        if (inString) {
            feedStringChar(c);
        } else {
            feedStructuralChar(c);
        }
    }

    private void feedStringChar(char c) {
        if (unicodeRemaining > 0) {
            unicodeBuf.append(c);
            if (--unicodeRemaining == 0) {
                try {
                    emit((char) Integer.parseInt(unicodeBuf.toString(), 16));
                } catch (NumberFormatException ignored) {
                    // malformed unicode escape — drop it
                }
            }
            return;
        }

        if (escape) {
            escape = false;
            switch (c) {
                case 'n' -> emit('\n');
                case 't' -> emit('\t');
                case 'r' -> emit('\r');
                case 'b' -> emit('\b');
                case 'f' -> emit('\f');
                case '"' -> emit('"');
                case '\\' -> emit('\\');
                case '/' -> emit('/');
                case 'u' -> {
                    unicodeRemaining = 4;
                    unicodeBuf.setLength(0);
                }
                default -> emit(c); // lenient: unknown escape -> literal char
            }
            return;
        }

        if (c == '\\') {
            escape = true;
        } else if (c == '"') {
            endString();
        } else {
            emit(c);
        }
    }

    private void feedStructuralChar(char c) {
        switch (c) {
            case '{' -> {
                stack.push('{');
                expectingKey = true;
            }
            case '[' -> {
                stack.push('[');
                expectingKey = false;
            }
            case '}', ']' -> {
                if (!stack.isEmpty()) stack.pop();
            }
            case ':' -> expectingKey = false;
            case ',' -> expectingKey = !stack.isEmpty() && stack.peek() == '{';
            case '"' -> beginString();
            default -> { /* whitespace, numbers, true/false/null: ignored */ }
        }
    }

    private void beginString() {
        inString = true;
        escape = false;
        unicodeRemaining = 0;
        currentString.setLength(0);

        boolean inObject = !stack.isEmpty() && stack.peek() == '{';
        currentIsKey = inObject && expectingKey;
        boolean atRootObject = stack.size() == 1 && inObject;
        streamingCurrentValue = !currentIsKey && atRootObject && "description".equals(lastKey);
    }

    private void endString() {
        inString = false;
        if (currentIsKey) {
            lastKey = currentString.toString();
        }
        currentIsKey = false;
        streamingCurrentValue = false;
    }

    private void emit(char ch) {
        currentString.append(ch);
        if (streamingCurrentValue) {
            prose.accept(String.valueOf(ch));
        }
    }
}
