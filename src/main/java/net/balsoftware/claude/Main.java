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

//        String model     = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-opus-4-6");
//        String model     = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-4-6");
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
        System.out.println("Tip: paste multi-line content, then press Enter twice to send.");

        Scanner scanner = new Scanner(System.in);
        ClaudeResponse last = null;

        while (true) {
            System.out.print("\nYou> ");
            String input = readMultiLineInput(scanner);

            switch (input.toLowerCase()) {
                case "quit"  -> { System.out.println("Bye!"); return; }
                case "reset" -> {
                    session.resetConversation();
                    System.out.println("[Conversation history cleared — context preserved]");
                    continue;
                }
                case "write" -> {
                    if (last == null) System.out.println("[No response to write yet]");
                    else { session.writeFiles(last); System.out.println("[Files written to ./generated/]"); }
                    continue;
                }
                case "show" -> {
                    if (last == null)           System.out.println("[No response yet]");
                    else if (!last.hasFiles())  System.out.println("[No files in last response]");
                    else last.files().forEach(f -> {
                            System.out.println("\n===== " + f.path() + " =====");
                            System.out.println(f.content());
                        });
                    continue;
                }
                case "turns"  -> { System.out.println("[Turn pairs in history: " + (session.getTurnCount() / 2) + "]"); continue; }
                case "tokens" -> { System.out.println("[" + session.tokenSummary() + "]"); continue; }
                case "context" -> {
                    printContextFiles(session);
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
            }
        }
    }

    /**
     * Reads multi-line input from the user.
     * Continues reading lines until an empty line is encountered.
     * This allows pasting multi-line content from the clipboard.
     */
    private static String readMultiLineInput(Scanner scanner) {
        StringBuilder sb = new StringBuilder();
        String firstLine = scanner.nextLine().trim();
        
        // If first line is a command, return it immediately
        if (isCommand(firstLine)) {
            return firstLine;
        }
        
        sb.append(firstLine);
        
        // Read additional lines until we hit an empty line
        while (scanner.hasNextLine()) {
            String nextLine = scanner.nextLine();
            
            // Empty line signals end of multi-line input
            if (nextLine.trim().isEmpty()) {
                break;
            }
            
            sb.append("\n").append(nextLine);
        }
        
        return sb.toString().trim();
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
