package net.balsoftware.claude;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionPersistenceTest {

    private SessionPersistence persistence;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        this.persistence = new SessionPersistence(tempDir);
    }

    private ClaudeTurn createDummyTurn() {
        return new ClaudeTurn(
                "Hello",
                "Hi there",
                new ClaudeStructuredResponse(ClaudeStructuredResponse.Type.explanation, "Hi there", List.of()),
                100, 50, 10, 0
        );
    }

    private SessionSnapshot createDummySnapshot() {
        // Must match the 8 fields in your SessionSnapshot record:
        List<SerializableTurn> turns = List.of(new SerializableTurn("Hello", "Hi"));
        List<String> contextClasses = List.of("java.lang.String");
        String systemPrompt = "You are helpful";
        String now = java.time.Instant.now().toString();

        return new SessionSnapshot(
                turns,
                contextClasses,
                systemPrompt,
                now,
                100, 50, 10, 0
        );
    }

    @Test
    void saveAndLoadSessionSnapshot() throws IOException {
        SessionSnapshot original = createDummySnapshot();

        persistence.saveSnapshot("test-session", original);

        // Verify file exists at correct path
        Path sessionFilePath = tempDir.resolve("test-session").resolve("manifest.json");
        assertTrue(Files.exists(sessionFilePath), "manifest.json should exist at " + sessionFilePath);

        // Load
        SessionSnapshot loaded = persistence.loadSession("test-session");

        // Assert
        assertEquals(1, loaded.turns().size());
        assertEquals("Hello", loaded.turns().get(0).getUserMessage());
        assertEquals("java.lang.String", loaded.loadedContextClassNames().get(0));
    }

    @Test
    void listSessionsReturnsAllSessions() throws IOException {
        persistence.saveSnapshot("session1", createDummySnapshot());
        persistence.saveSnapshot("session2", createDummySnapshot());
        persistence.saveSnapshot("session3", createDummySnapshot());

        List<String> sessions = persistence.listSessions();
        assertEquals(3, sessions.size());
        assertTrue(sessions.contains("session1"));
    }

    @Test
    void deleteSessionRemovesDirectory() throws IOException {
        persistence.saveSnapshot("to-delete", createDummySnapshot());
        Path sessionDir = tempDir.resolve("to-delete");
        assertTrue(Files.exists(sessionDir));

        persistence.deleteSession("to-delete");
        assertFalse(Files.exists(sessionDir));
    }

    @Test
    void loadNonexistentSessionThrows() {
        assertThrows(IOException.class, () -> persistence.loadSession("nonexistent"));
    }

    @Test
    void roundTripPreservesAllFields() throws IOException {
        SessionSnapshot original = new SessionSnapshot(
                List.of(new SerializableTurn("Hello", "Hi there")),
                List.of("java.lang.String", "java.util.List"),
                "You are helpful",
                "2026-05-05T22:48:00Z",
                123, 45, 6, 7
        );

        persistence.saveSnapshot("round-trip", original);
        SessionSnapshot loaded = persistence.loadSession("round-trip");

        assertEquals(original.turns().size(), loaded.turns().size());
        assertEquals(original.turns().get(0).getUserMessage(), loaded.turns().get(0).getUserMessage());
        assertEquals(original.turns().get(0).getAssistantMessage(), loaded.turns().get(0).getAssistantMessage());
        assertEquals(original.loadedContextClassNames(), loaded.loadedContextClassNames());
        assertEquals(original.systemPrompt(), loaded.systemPrompt());
        assertEquals(original.timestamp(), loaded.timestamp());
        assertEquals(original.totalInputTokens(), loaded.totalInputTokens());
        assertEquals(original.totalOutputTokens(), loaded.totalOutputTokens());
        assertEquals(original.totalCacheCreationTokens(), loaded.totalCacheCreationTokens());
        assertEquals(original.totalCacheReadTokens(), loaded.totalCacheReadTokens());
    }

    @Test
    void saveAndLoadEmptySession() throws IOException {
        SessionSnapshot empty = new SessionSnapshot(
                List.of(),
                List.of(),
                "You are helpful",
                "2026-05-05T22:48:00Z",
                0, 0, 0, 0
        );

        persistence.saveSnapshot("empty-session", empty);
        SessionSnapshot loaded = persistence.loadSession("empty-session");

        assertNotNull(loaded);
        assertTrue(loaded.turns().isEmpty());
        assertTrue(loaded.loadedContextClassNames().isEmpty());
        assertEquals(0, loaded.totalInputTokens());
        assertEquals(0, loaded.totalOutputTokens());
    }

    @Test
    void saveSnapshotOverwritesExistingFile() throws IOException {
        persistence.saveSnapshot("session", new SessionSnapshot(
                List.of(new SerializableTurn("Q1", "A1")),
                List.of(),
                "prompt",
                "t1",
                1, 2, 3, 4
        ));

        persistence.saveSnapshot("session", new SessionSnapshot(
                List.of(new SerializableTurn("Q2", "A2")),
                List.of("java.lang.String"),
                "prompt2",
                "t2",
                10, 20, 30, 40
        ));

        SessionSnapshot loaded = persistence.loadSession("session");

        assertEquals(1, loaded.turns().size());
        assertEquals("Q2", loaded.turns().get(0).getUserMessage());
        assertEquals("prompt2", loaded.systemPrompt());
        assertEquals(10, loaded.totalInputTokens());
    }

    @Test
    void listSessionsIgnoresFilesAtRoot() throws IOException {
        Files.writeString(tempDir.resolve("not-a-session.txt"), "hello");
        persistence.saveSnapshot("session-a", createDummySnapshot());

        List<String> sessions = persistence.listSessions();

        assertEquals(1, sessions.size());
        assertTrue(sessions.contains("session-a"));
    }

    @Test
    void deleteSessionRemovesNestedContents() throws IOException {
        persistence.saveSnapshot("nested", createDummySnapshot());

        Path nestedFile = tempDir.resolve("nested").resolve("extra.txt");
        Files.writeString(nestedFile, "data");
        assertTrue(Files.exists(nestedFile));

        persistence.deleteSession("nested");

        assertFalse(Files.exists(tempDir.resolve("nested")));
    }

    @Test
    void loadCorruptJsonThrows() throws IOException {
        Path sessionDir = tempDir.resolve("bad");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("manifest.json"), "{not valid json");

        assertThrows(IOException.class, () -> persistence.loadSession("bad"));
    }
}
