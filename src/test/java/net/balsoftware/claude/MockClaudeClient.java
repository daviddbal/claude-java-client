package net.balsoftware.claude;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MockClaudeClient simulates ClaudeClient for testing prompt caching.
 */
public class MockClaudeClient extends ClaudeClient {
    private boolean firstCall = true;

    public MockClaudeClient(String systemPrompt) {
        super("DUMMY_KEY", 1000, systemPrompt);
    }

    @Override
    public RawResponse send(String model, List<ClaudeMessage> conversationTurns) {
        if (firstCall) {
            firstCall = false;
            return new RawResponse(
                    "mock response 1",
                    10,   // input tokens
                    5,    // output tokens
                    10,   // cache creation tokens
                    0     // cache read tokens
            );
        } else {
            return new RawResponse(
                    "mock response 2",
                    5,
                    3,
                    0,
                    10    // cache read tokens
            );
        }
    }
}