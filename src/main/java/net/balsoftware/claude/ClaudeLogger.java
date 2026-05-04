package net.balsoftware.claude;

public class ClaudeLogger {
    public static void logResponse(String model, String prompt, OKHttpClaudeClient.RawResponse resp) {
        System.out.printf("[Claude] model=%s | prompt='%s'%n", model, truncate(prompt, 80));
        System.out.printf("[Claude] tokens → in=%d, out=%d, cacheWrite=%d, cacheRead=%d%n",
                resp.inputTokens(),
                resp.outputTokens(),
                resp.cacheCreationTokens(),
                resp.cacheReadTokens());
        System.out.println("--------------------------------------------------");
    }

    private static String truncate(String str, int max) {
        return str.length() <= max ? str : str.substring(0, max) + "...";
    }
}