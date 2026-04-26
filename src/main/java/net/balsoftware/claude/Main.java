package net.balsoftware.claude;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {
        String apiKey = EnvConfig.getClaudeApiKey();
        System.out.println("Loaded key: " + (apiKey != null));
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: CLAUDE_API_KEY environment variable not set.");
            System.exit(1);
        }

        String model  = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-haiku-4-5-20251001");
        int maxTokens = Integer.parseInt(System.getenv().getOrDefault("CLAUDE_MAX_TOKENS", String.valueOf(4096*4)));

        ClaudeSession session = ClaudeSession.builder()
                .apiKey(apiKey)
                .model(model)
                .maxTokens(maxTokens)
                .sourceRoots(List.of(Path.of("src/main/java")))
                .outputRoot(Path.of("generated"))
                .contextFilesRoot(Path.of("context-files"))
                .build();

        session.loadContext(List.of());
        printContextFiles(session);

        System.out.println("Claude Coding Assistant — model: " + model + " | max_tokens: " + maxTokens);
        System.out.println("Commands:");
        System.out.println("  reset  — clear conversation history (keeps loaded context)");
        System.out.println("  write  — write files from last response to ./generated/");
        System.out.println("  show   — print generated file contents to the terminal");
        System.out.println("  turns  — show number of turns in history");
        System.out.println("  tokens — show total token usage this session");
        System.out.println("  context — show loaded context files");
        System.out.println("  quit   — exit");
        System.out.println("--------------------------------------------------");
        System.out.println("Tip: place any files in ./context-files/ to add them to Claude's context.");
        System.out.println("(Press Enter after each line. Finish input with Ctrl-D on Unix/macOS or Ctrl-Z on Windows.)");

        Scanner scanner = new Scanner(System.in);
        ClaudeResponse last = null;

        while (true) {
            System.out.print("\nYou> ");
            System.out.flush();
            String input = MultiLineInputReader.readMultiLineInput(scanner);
            System.out.println("input=" + input);
            System.exit(0);

            switch (input.toLowerCase()) {
                case "quit"  -> { System.out.println("Bye!"); System.out.flush(); return; }
                case "reset" -> {
                    session.resetConversation();
                    System.out.println("[Conversation history cleared — context preserved]");
                    System.out.flush();
                    continue;
                }
                case "write" -> {
                    if (last == null) System.out.println("[No response to write yet]");
                    else { session.writeFiles(last); System.out.println("[Files written to ./generated/]"); }
                    System.out.flush();
                    continue;
                }
                case "show" -> {
                    if (last == null)           System.out.println("[No response yet]");
                    else if (!last.hasFiles())  System.out.println("[No files in last response]");
                    else last.files().forEach(f -> {
                            System.out.println("\n===== " + f.path() + " =====");
                            System.out.println(f.content());
                        });
                    System.out.flush();
                    continue;
                }
                case "turns"  -> { System.out.println("[Turn pairs in history: " + (session.getTurnCount() / 2) + "]"); System.out.flush(); continue; }
                case "tokens" -> { System.out.println("[" + session.tokenSummary() + "]"); System.out.flush(); continue; }
                case "context" -> {
                    printContextFiles(session);
                    System.out.flush();
                    continue;
                }
                default -> {}
            }

            if (input.isBlank()) continue;

            try {
                last = session.ask(input);

                System.out.println("\nClaude> " + last.description());
                System.out.printf(
                        "[Tokens — in: %d, out: %d | cache write: %d, cache read: %d | %s]%n",
                        last.inputTokens(), last.outputTokens(),
                        last.cacheCreationTokens(), last.cacheReadTokens(),
                        getCacheStatusForResponse(last));

                if (last.hasFiles()) {
                    System.out.println("Generated files:");
                    last.files().forEach(f -> System.out.println("  " + f.path()));
                    System.out.println("Type 'show' to view contents, 'write' to save them.");
                }
            } catch (Exception e) {
                System.err.println("[Error] " + e.getMessage());
                e.printStackTrace(System.err);
            }
            System.out.flush();
        }
    }

    private static boolean isCommand(String input) {
        String lower = input.toLowerCase().trim();
        return lower.equals("quit") || lower.equals("reset") || lower.equals("write") ||
                lower.equals("show") || lower.equals("turns") || lower.equals("tokens") ||
                lower.equals("context");
    }

    private static void printContextFiles(ClaudeSession session) {
        List<String> contextFiles = session.getLoadedContextFiles();
        if (contextFiles.isEmpty()) {
            System.out.println("[No context files loaded]");
        } else {
            System.out.println("Loaded context files ("+contextFiles.size()+"):");
            contextFiles.forEach(f -> System.out.println("  " + f));
        }
    }

    private static String getCacheStatusForResponse(ClaudeResponse response) {
        if (response.cacheReadTokens() > 0) {
            return "CACHE HIT ✓";
        } else if (response.cacheCreationTokens() > 0) {
            return "CACHE MISS (written)";
        } else {
            return "NO CACHE";
        }
    }
}