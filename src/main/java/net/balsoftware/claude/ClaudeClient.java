package net.balsoftware.claude;

import java.io.IOException;
import java.util.List;

public interface ClaudeClient {
    OKHttpClaudeClient.RawResponse send(String model, List<ClaudeMessage> messages) throws IOException;
    default void logCacheStatus(ClaudeSession session) {
        System.out.println("[CACHE] " + session.tokenSummary());
    }
}