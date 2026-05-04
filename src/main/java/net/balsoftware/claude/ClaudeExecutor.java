package net.balsoftware.claude;

import java.io.IOException;

/**
 * Handles single-turn execution: invokes Claude with a message and parses response.
 * Responsibility: Execute and parse, not caching or token tracking.
 */
public class ClaudeExecutor {

    private final ClaudeRunner runner;
    private final ClaudeResponseParser responseParser;

    public ClaudeExecutor(ClaudeRunner runner, ClaudeResponseParser responseParser) {
        this.runner = runner;
        this.responseParser = responseParser;
    }

    /**
     * Executes a single request to Claude and returns the structured response with tokens.
     */
    public ClaudeStructuredResponseWithTokens execute(String model, String userMessage) throws IOException {
        if (runner == null) {
            throw new IllegalStateException("Runner must be initialized before execution.");
        }

        return runner.runStructured(model, userMessage);
    }
}
