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
}
