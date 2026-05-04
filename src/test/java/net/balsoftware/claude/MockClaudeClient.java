package net.balsoftware.claude;

import java.util.List;

public class MockClaudeClient implements ClaudeClient {

    private final int minCacheTokens;

    public MockClaudeClient() {
        this(4096);
    }

    public MockClaudeClient(int minCacheTokens) {
        this.minCacheTokens = minCacheTokens;
    }

    @Override
    public OKHttpClaudeClient.RawResponse send(String model, List<ClaudeMessage> conversationTurns) {
        String prompt = conversationTurns.get(conversationTurns.size() - 1).content();
        int inputTokens = conversationTurns.stream()
                .mapToInt(msg -> msg.content().length())
                .sum();
        int outputTokens = 10;

        // Only "cacheable" if input tokens meet threshold, like Claude
        boolean largeEnoughForCache = inputTokens >= minCacheTokens;

        OKHttpClaudeClient.RawResponse response = new OKHttpClaudeClient.RawResponse(
                "mock: " + prompt,
                inputTokens,
                outputTokens,
                largeEnoughForCache ? inputTokens : 0, // cacheCreationTokens
                0  // cacheReadTokens: always zero, session deals with cache hit
        );

        // ---------------- LOGGING ----------------
        ClaudeLogger.logResponse(model, prompt, response);

        return response;
    }
}