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

        String model = System.getenv().getOrDefault(
                "CLAUDE_MODEL",
                "claude-haiku-4-5-20251001"
        );

        int maxTokens = Integer.parseInt(
                System.getenv().getOrDefault("CLAUDE_MAX_TOKENS", String.valueOf(4096 * 4))
        );

        ClaudeClientFactory factory = config -> new ClaudeClient(
                config.apiKey(),
                config.maxTokens(),
                config.systemPrompt()
        );

        ClaudeSession session = ClaudeSession.builder()
                .apiKey(apiKey)
                .model(model)
                .maxTokens(maxTokens)
                .sourceRoots(List.of(Path.of("src/main/java")))
                .outputRoot(Path.of("generated"))
                .contextFilesRoot(Path.of("context-files"))
                .clientFactory(factory)
                .build();

        session.loadContext(List.of());
        printContextFiles(session);

        System.out.println("Claude Coding Assistant — model: " + model + " | max_tokens: " + maxTokens);
        System.out.println("""
                Commands:
                  reset   — clear conversation history (keeps loaded context)
                  write   — write files from last response to ./generated/
                  show    — print generated file contents
                  turns   — show number of turns
                  tokens  — show token usage
                  context — show loaded context files
                  quit    — exit
                --------------------------------------------------
                Input modes:
                  - Single line: just type and press Enter
                  - Multi-line: start with :: and end with <<<END
                """);

        Scanner scanner = new Scanner(System.in);
        InputReader inputReader = new MultiLineInputReader();

        ClaudeStructuredResponseWithTokens last = null; // updated to WithTokens

        while (true) {
            System.out.print("\nYou> ");
            System.out.flush();

            String input = inputReader.read(scanner);

            switch (input.toLowerCase()) {
                case "quit" -> {
                    System.out.println("Bye!");
                    return;
                }
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
                case "context" -> {
                    printContextFiles(session);
                    continue;
                }
                default -> {}
            }

            if (input.isBlank()) continue;

            try {
                System.out.println("[DEBUG] Calling session.ask()");
                last = session.ask(input); // Now returns ClaudeStructuredResponseWithTokens

                System.out.println("\nClaude> " +
                        (last.description() != null ? last.description() : "[No description]"));

                System.out.printf(
                        "[Tokens — in: %d, out: %d | cache write: %d, cache read: %d | %s]%n",
                        last.inputTokens(),
                        last.outputTokens(),
                        last.cacheCreationTokens(),
                        last.cacheReadTokens(),
                        session.isCacheHitObserved() ? "CACHE HIT ✓" : "NO CACHE"
                );

                if (last.hasFiles()) {
                    System.out.println("Generated files:");
                    last.files().forEach(f -> System.out.println("  " + f.path()));
                    System.out.println("\nType 'write' to save these files to ./generated/");
                }

            } catch (Exception e) {
                System.err.println("[Error] " + e.getMessage());
                e.printStackTrace(System.err);
            }
        }
    }

    private static void printContextFiles(ClaudeSession session) {
        List<String> contextFiles = session.getLoadedContextFiles();
        if (contextFiles.isEmpty()) {
            System.out.println("[No context files loaded]");
        } else {
            System.out.println("Loaded context files (" + contextFiles.size() + "):");
            contextFiles.forEach(f -> System.out.println("  " + f));
        }
    }
}