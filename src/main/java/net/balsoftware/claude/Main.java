package net.balsoftware.claude;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Interactive CLI for the Claude coding assistant.
 *
 * Required env var : CLAUDE_API_KEY
 * Optional env var : CLAUDE_MODEL      (default: claude-opus-4-5)
 * Optional env var : CLAUDE_MAX_TOKENS (default: 4096)
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String apiKey = EnvConfig.getClaudeApiKey();
        System.out.println("Loaded key: " + (apiKey != null));
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: CLAUDE_API_KEY environment variable not set.");
            System.exit(1);
        }

//        String model     = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-opus-4-6");
//        String model     = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-4-6");
        String model     = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-haiku-4-5-20251001");
        int maxTokens    = Integer.parseInt(System.getenv().getOrDefault("CLAUDE_MAX_TOKENS", "4096"));

        ClaudeSession session = ClaudeSession.builder()
                .apiKey(apiKey)
                .model(model)
                .maxTokens(maxTokens)
                .sourceRoots(List.of(Path.of("src/main/java")))
                .outputRoot(Path.of("generated"))
                .build();

        session.loadContext(List.of(
//                net.balsoftware.trader.TraderParameters.class
        ));

        System.out.println("Claude Coding Assistant — model: " + model + " | max_tokens: " + maxTokens);
        System.out.println("Commands:");
        System.out.println("  reset  — clear conversation history (keeps loaded context)");
        System.out.println("  write  — write files from last response to ./generated/");
        System.out.println("  show   — print generated file contents to the terminal");
        System.out.println("  turns  — show number of turns in history");
        System.out.println("  tokens — show total token usage this session");
        System.out.println("  quit   — exit");
        System.out.println("--------------------------------------------------");

        Scanner scanner = new Scanner(System.in);
        ClaudeResponse last = null;

        while (true) {
            System.out.print("\nYou> ");
            String line = scanner.nextLine().trim();

            switch (line.toLowerCase()) {
                case "quit" -> { System.out.println("Bye!"); return; }
                case "reset" -> {
                    session.resetConversation();
                    System.out.println("[Conversation history cleared — context preserved]");
                    continue;
                }
                case "write" -> {
                    if (last == null) {
                        System.out.println("[No response to write yet]");
                    } else {
                        session.writeFiles(last);
                        System.out.println("[Files written to ./generated/]");
                    }
                    continue;
                }
                case "show" -> {
                    if (last == null) {
                        System.out.println("[No response yet]");
                    } else if (!last.hasFiles()) {
                        System.out.println("[No files in last response]");
                    } else {
                        last.files().forEach(f -> {
                            System.out.println("\n===== " + f.path() + " =====");
                            System.out.println(f.content());
                        });
                    }
                    continue;
                }
                case "turns" -> {
                    System.out.println("[Turn pairs in history: " + (session.getTurnCount() / 2) + "]");
                    continue;
                }
                case "tokens" -> {
                    System.out.println("[" + session.tokenSummary() + "]");
                    continue;
                }
                default -> {}
            }

            if (line.isBlank()) continue;

            try {
                last = session.ask(line);

                System.out.println("\nClaude> " + last.description());
                System.out.printf("[Tokens — this turn: in=%d out=%d | session total: in=%d out=%d]%n",
                        last.inputTokens(), last.outputTokens(),
                        session.getTotalInputTokens(), session.getTotalOutputTokens());

                if (last.hasFiles()) {
                    System.out.println("Generated files:");
                    last.files().forEach(f -> System.out.println("  " + f.path()));
                    System.out.println("Type 'show' to view contents, 'write' to save them.");
                }
            } catch (Exception e) {
                System.err.println("[Error] " + e.getMessage());
            }
        }
    }
}