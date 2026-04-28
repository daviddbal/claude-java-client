package net.balsoftware.claude;

public interface ClaudeClientFactory {
    ClaudeClient createClient(ClaudeClientConfig config);
}