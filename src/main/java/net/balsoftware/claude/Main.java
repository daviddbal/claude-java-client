package net.balsoftware.claude;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class Main {

    // Toggle CLI logging here
    private static final boolean CLI_LOGGING_ENABLED = true;

    public static void main(String[] args) throws Exception {

        String apiKey = EnvConfig.getClaudeApiKey();
        System.out.println("Loaded key: " + (apiKey != null));

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: CLAUDE_API_KEY environment variable not set.");
            System.exit(1);
        }

        String model = System.getenv().getOrDefault(
                "CLAUDE_MODEL",
                ClaudeModel.defaultModel().id()
        );

        int maxTokens = Integer.parseInt(
                System.getenv().getOrDefault("CLAUDE_MAX_TOKENS", String.valueOf(OKHttpClaudeClient.DEFAULT_MAX_TOKENS))
        );

        boolean streaming = Boolean.parseBoolean(System.getenv().getOrDefault("CLAUDE_STREAM", "false"));

        ClaudeClientFactory factory = config -> new OKHttpClaudeClient(
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

        SessionPersistence persistence = new SessionPersistence(Path.of("sessions"));

        System.out.println("Claude Coding Assistant — model: " + model + " | max_tokens: " + maxTokens);
        System.out.println("""
                Commands:
                  reset        — clear conversation history (keeps loaded context)
                  write        — write files from last response to ./generated/
                  show         — print generated file contents
                  turns        — show number of turns
                  tokens       — show token usage
                  context      — show loaded context files
                  save <name>  — persist the current session to ./sessions/<name>/
                  resume <name>— restore a previously saved session
                  sessions     — list saved sessions
                  quit         — exit
                --------------------------------------------------
                Input modes:
                  - Single line: just type and press Enter
                  - Multi-line: start with :: and end with <<<END
                """);

        Scanner scanner = new Scanner(System.in);
        InputReader inputReader = new MultiLineInputReader();

        ClaudeStructuredResponseWithTokens last = null;

        while (true) {
            System.out.print("\nYou> ");
            System.out.flush();

            String input = inputReader.read(scanner);
            String trimmedInput = input.trim();

            if (trimmedInput.regionMatches(true, 0, "save ", 0, 5)) {
                String name = trimmedInput.substring(5).trim();
                if (name.isBlank()) {
                    System.out.println("[Usage: save <name>]");
                } else {
                    try {
                        persistence.saveSession(name, session);
                        System.out.println("[Session saved to "
                                + persistence.getStorageRoot().resolve(name) + "]");
                    } catch (Exception e) {
                        System.err.println("[Error saving session: " + e.getMessage() + "]");
                    }
                }
                continue;
            }

            if (trimmedInput.regionMatches(true, 0, "resume ", 0, 7)) {
                String name = trimmedInput.substring(7).trim();
                if (name.isBlank()) {
                    System.out.println("[Usage: resume <name>]");
                } else {
                    try {
                        SessionSnapshot snapshot = persistence.loadSession(name);
                        session.restore(snapshot);
                        last = session.getLastResponse();
                        System.out.println("[Session '" + name + "' restored — "
                                + session.getTurnCount() + " turns | " + session.tokenSummary() + "]");
                    } catch (Exception e) {
                        System.err.println("[Error resuming session: " + e.getMessage() + "]");
                    }
                }
                continue;
            }

            switch (input.toLowerCase()) {
                case "quit" -> {
                    System.out.println("Bye!");
                    return;
                }
                case "reset" -> {
                    session.resetConversation();
                    if (CLI_LOGGING_ENABLED)
                        System.out.println("[Conversation history cleared — context preserved]");
                    continue;
                }
                case "write" -> {
                    if (last == null) {
                        if (CLI_LOGGING_ENABLED) System.out.println("[No response to write yet]");
                    } else {
                        session.writeFiles(last);
                        if (CLI_LOGGING_ENABLED) System.out.println("[Files written to ./generated/]");
                    }
                    continue;
                }
                case "show" -> {
                    if (last == null) {
                        if (CLI_LOGGING_ENABLED) System.out.println("[No response yet]");
                    } else if (!last.hasFiles()) {
                        if (CLI_LOGGING_ENABLED) System.out.println("[No files in last response]");
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
                case "sessions" -> {
                    try {
                        List<String> names = persistence.listSessions();
                        if (names.isEmpty()) {
                            System.out.println("[No saved sessions]");
                        } else {
                            System.out.println("Saved sessions:");
                            names.forEach(n -> System.out.println("  " + n));
                        }
                    } catch (Exception e) {
                        System.err.println("[Error listing sessions: " + e.getMessage() + "]");
                    }
                    continue;
                }
                case "save", "resume" -> {
                    System.out.println("[Usage: " + input.toLowerCase() + " <name>]");
                    continue;
                }
                default -> {}
            }

            if (input.isBlank()) continue;

            try {
                if (streaming) {
                    // Stream the readable description prose as it is generated.
                    System.out.print("\nClaude> ");
                    System.out.flush();
                    last = session.askStreamingProse(input, p -> {
                        System.out.print(p);
                        System.out.flush();
                    });
                    System.out.println();
                } else {
                    last = session.ask(input); // returns ClaudeStructuredResponseWithTokens
                }

                if (CLI_LOGGING_ENABLED) {
                    if (!streaming) {
                        // Non-streaming: print the description now (streaming already printed it live).
                        System.out.println("\nClaude> " +
                                (last.description() != null ? last.description() : "[No description]"));
                    }

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