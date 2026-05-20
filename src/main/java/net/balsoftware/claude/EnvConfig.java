package net.balsoftware.claude;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvConfig {
    // ignoreIfMissing(): don't crash when there is no .env file. dotenv-java still
    // resolves keys from real environment variables, so env-var-only usage works too.
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

    public static String getClaudeApiKey() {
        return dotenv.get("CLAUDE_API_KEY");
    }
}
