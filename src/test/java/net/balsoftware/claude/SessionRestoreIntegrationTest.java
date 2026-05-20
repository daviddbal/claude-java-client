package net.balsoftware.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the restore half of session persistence: a saved session can be
 * rehydrated into a brand new {@link ClaudeSession} that continues the
 * conversation (prior turns are replayed to the API) and preserves token totals.
 */
class SessionRestoreIntegrationTest {

    /**
     * Mock client factory that records the conversation passed to each send() call
     * and echoes the last prompt back in a structured explanation response.
     */
    private static ClaudeClientFactory recordingFactory(AtomicReference<List<ClaudeMessage>> lastConversation) {
        return config -> new OKHttpClaudeClient(config.apiKey(), config.maxTokens(), config.systemPrompt()) {
            @Override
            public RawResponse send(String model, List<ClaudeMessage> conversation) {
                lastConversation.set(new ArrayList<>(conversation));
                String lastPrompt = conversation.get(conversation.size() - 1).content();
                return new RawResponse(
                        "{\"type\":\"explanation\",\"description\":\"answer to: " + lastPrompt + "\",\"files\":[]}",
                        10, 5, 0, 0
                );
            }
        };
    }

    @Test
    void restoredSessionContinuesConversationAndPreservesState(@TempDir Path tempDir) throws IOException {
        // ---- build & save an original session ----
        ClaudeSession original = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(recordingFactory(new AtomicReference<>()))
                .build();

        original.loadContext(List.of(String.class));
        original.ask("First question");
        original.ask("Second question");

        SessionPersistence persistence = new SessionPersistence(tempDir);
        persistence.saveSession("resume-me", original);

        // ---- restore into a brand new session with a fresh recording client ----
        SessionSnapshot snapshot = persistence.loadSession("resume-me");

        AtomicReference<List<ClaudeMessage>> restoredLast = new AtomicReference<>();
        ClaudeSession restored = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(recordingFactory(restoredLast))
                .build();

        restored.restore(snapshot, List.of(String.class));

        // state was rehydrated
        assertEquals(2, restored.getTurnCount(), "restored session should have 2 turns");
        assertEquals(List.of("First question", "Second question"), restored.getConversationHistory());
        assertEquals(original.getTotalInputTokens(), restored.getTotalInputTokens());
        assertEquals(original.getTotalOutputTokens(), restored.getTotalOutputTokens());

        // continuation: the next ask must replay prior turns to the API
        restored.ask("Third question");

        List<ClaudeMessage> sent = restoredLast.get();
        assertNotNull(sent, "client should have received a conversation");
        assertEquals(5, sent.size(), "2 prior turns (4 messages) + new user message");

        assertEquals(ClaudeRole.USER, sent.get(0).role());
        assertEquals("First question", sent.get(0).content());
        assertEquals(ClaudeRole.ASSISTANT, sent.get(1).role());
        assertEquals("answer to: First question", sent.get(1).content());
        assertEquals(ClaudeRole.USER, sent.get(2).role());
        assertEquals("Second question", sent.get(2).content());
        assertEquals(ClaudeRole.USER, sent.get(4).role());
        assertEquals("Third question", sent.get(4).content());

        assertEquals(3, restored.getTurnCount(), "third turn should be appended after continuation");
    }

    @Test
    void restorePreservesStructuredResponseInTurns(@TempDir Path tempDir) throws IOException {
        ClaudeSession original = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(recordingFactory(new AtomicReference<>()))
                .build();
        original.loadContext(List.of());
        original.ask("hello");

        SessionPersistence persistence = new SessionPersistence(tempDir);
        persistence.saveSession("structured", original);
        SessionSnapshot snapshot = persistence.loadSession("structured");

        // the full structured response survives serialization
        SerializableTurn turn = snapshot.turns().get(0);
        assertNotNull(turn.getStructured(), "structured response should be persisted");
        assertEquals(ClaudeStructuredResponse.Type.explanation, turn.getStructured().type());
        assertEquals("answer to: hello", turn.getStructured().description());

        // and is available as the last response after restore
        ClaudeSession restored = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(recordingFactory(new AtomicReference<>()))
                .build();
        restored.restore(snapshot, List.of());

        assertNotNull(restored.getLastResponse(), "last response should be set from the final turn");
        assertEquals("answer to: hello", restored.getLastResponse().structured().description());
    }

    @Test
    void restoresLegacyManifestWithoutStructuredField(@TempDir Path tempDir) throws IOException {
        // Simulate a session saved before turns carried a structured response. The 2-arg
        // SerializableTurn constructor leaves structured null, and @JsonInclude(NON_NULL)
        // omits it from the manifest — exactly the shape of an old saved session.
        SessionSnapshot legacy = new SessionSnapshot(
                List.of(
                        new SerializableTurn("First question", "First answer"),
                        new SerializableTurn("Second question", "Second answer")
                ),
                List.of(),
                "system prompt",
                "2026-01-01T00:00:00Z",
                42, 13, 0, 0
        );

        SessionPersistence persistence = new SessionPersistence(tempDir);
        persistence.saveSnapshot("legacy", legacy);

        // sanity check: the on-disk manifest really has no "structured" key
        String manifest = Files.readString(tempDir.resolve("legacy").resolve("manifest.json"));
        assertFalse(manifest.contains("\"structured\""), "legacy manifest should not contain structured");

        SessionSnapshot loaded = persistence.loadSession("legacy");

        AtomicReference<List<ClaudeMessage>> sent = new AtomicReference<>();
        ClaudeSession restored = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(recordingFactory(sent))
                .build();

        // Exercises SerializableTurn.toClaudeTurn()'s null-structured fallback.
        restored.restore(loaded, List.of());

        assertEquals(2, restored.getTurnCount());
        assertEquals(List.of("First question", "Second question"), restored.getConversationHistory());
        assertEquals(42, restored.getTotalInputTokens());

        // Continuation still replays the legacy turns (assistant text preserved).
        restored.ask("Third question");
        List<ClaudeMessage> conv = sent.get();
        assertEquals("First question", conv.get(0).content());
        assertEquals("First answer", conv.get(1).content());
        assertEquals("Third question", conv.get(conv.size() - 1).content());
    }

    @Test
    void restoreWithoutExplicitContextResolvesStoredClassNames(@TempDir Path tempDir) throws IOException {
        ClaudeSession original = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(recordingFactory(new AtomicReference<>()))
                .build();
        original.loadContext(List.of(String.class));
        original.ask("hi");

        SessionPersistence persistence = new SessionPersistence(tempDir);
        persistence.saveSession("auto-context", original);
        SessionSnapshot snapshot = persistence.loadSession("auto-context");

        ClaudeSession restored = ClaudeSession.builder()
                .apiKey("DUMMY_KEY")
                .clientFactory(recordingFactory(new AtomicReference<>()))
                .build();

        // single-arg restore resolves "java.lang.String" via Class.forName
        restored.restore(snapshot);

        assertEquals(List.of("java.lang.String"), restored.getLoadedContextClassNames());
        assertEquals(1, restored.getTurnCount());
    }
}
