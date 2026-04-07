package net.balsoftware.claude;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvConfig {
    private static final Dotenv dotenv = Dotenv.load();

    public static String getClaudeApiKey() {
        return dotenv.get("CLAUDE_API_KEY");
    }
}
