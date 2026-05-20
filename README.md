# claude-java-client

An interactive CLI (and small library) for multi-turn conversations with Claude about your
Java codebase. You point it at source/context files, ask Claude to generate or modify code,
and it returns structured JSON with file contents you can write to disk. Conversations can be
**saved and resumed** across runs.

## Features

- **Multi-turn chat** with conversation history and turn-based context.
- **Structured responses** — Claude replies in JSON (`type`, `description`, `files[]`); generated files are written under `generated/`.
- **Session persistence** — save the current conversation (turns, context, token totals) to disk and resume it later, in the CLI (`save` / `resume` / `sessions`) or via the library (`SessionPersistence` + `ClaudeSession.restore`).
- **Prompt caching** — the system prompt is sent with Anthropic's ephemeral cache control so repeated requests with the same context are cheaper.
- **Token tracking** — running totals for input/output and cache read/write tokens.
- **Path-traversal-safe file writing** — model-supplied paths that escape the output root are rejected.

## Requirements

- **Java 25** (the build targets Java 25; uses records, text blocks, switch expressions)
- **Maven**
- An **Anthropic API key** ([console.anthropic.com](https://console.anthropic.com/settings/keys))

## Setup

Provide your API key as a `CLAUDE_API_KEY` environment variable, or in a `.env`
file in the project root (see `.env.example`):

```
CLAUDE_API_KEY=sk-ant-...
```

Optionally drop any reference material you want Claude to see into `context-files/` — its
contents are included in the system prompt on startup.

## Build & run

```bash
mvn package
java -jar target/claude-1.0.0-SNAPSHOT.jar
```

(`mvn package` produces a runnable shaded jar via the shade plugin.)

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `CLAUDE_API_KEY` | _(required, from `.env`)_ | Anthropic API key |
| `CLAUDE_MODEL` | `claude-haiku-4-5-20251001` | Full model id to use |
| `CLAUDE_MAX_TOKENS` | `16384` | Max output tokens per response |
| `CLAUDE_COLLECT_RAW` | `false` | When `true`, dumps every raw request/response to `collected-claude-requests/` and `collected-claude-responses/` (debugging only) |

## CLI commands

| Command | Action |
|---|---|
| `reset` | Clear conversation history (keeps loaded context) |
| `write` | Write files from the last response to `./generated/` |
| `show` | Print the last response's generated file contents |
| `turns` | Show the number of turns |
| `tokens` | Show token usage |
| `context` | Show loaded context files |
| `save <name>` | Persist the current session to `./sessions/<name>/` |
| `resume <name>` | Restore a previously saved session |
| `sessions` | List saved sessions |
| `quit` | Exit |

### Input modes

- **Single line** — type and press Enter.
- **Multi-line** — start with `::`, then end the block with a line containing `<<<END`.

Anything that isn't a command is sent to Claude as a prompt.

## Library usage

```java
ClaudeSession session = ClaudeSession.builder()
        .apiKey(apiKey)
        .model(ClaudeModel.HAIKU_5.id())
        .build();

session.loadContext(List.of(MyClass.class));   // include classes as context
ClaudeStructuredResponseWithTokens response = session.ask("Add a builder to MyClass");
session.writeFiles(response);                  // write generated files to ./generated/
```

### Saving and resuming a session

```java
SessionPersistence persistence = new SessionPersistence(Path.of("sessions"));

// Save
persistence.saveSession("my-session", session);

// Later (possibly a new process): load and restore into a fresh session
SessionSnapshot snapshot = persistence.loadSession("my-session");
ClaudeSession restored = ClaudeSession.builder().apiKey(apiKey).build();
restored.restore(snapshot);          // resolves stored context classes automatically
// or: restored.restore(snapshot, List.of(MyClass.class));  // supply context explicitly

restored.ask("Continue where we left off");   // prior turns are replayed to the API
```

`SessionPersistence` also offers `listSessions()` and `deleteSession(name)`.

## Directories

| Path | Contents |
|---|---|
| `context-files/` | Reference material included in the system prompt |
| `generated/` | Files written from Claude's responses |
| `sessions/` | Saved sessions (`<name>/manifest.json`) |
| `collected-claude-*/` | Raw request/response dumps (only when `CLAUDE_COLLECT_RAW=true`) |

All four are git-ignored.
