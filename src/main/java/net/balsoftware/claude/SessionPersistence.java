package net.balsoftware.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Handles persistence of ClaudeSession state.
 */
public class SessionPersistence {

    private final Path storageRoot;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionPersistence(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    /**
     * Saves a full ClaudeSession by introspecting its current state.
     */
    public void saveSession(String sessionName, ClaudeSession session) throws IOException {
        List<SerializableTurn> turns = session.getConversationTurns();
        if (turns.stream().anyMatch(t -> t.getUserMessage() == null)) {
            throw new IllegalStateException("Invalid turn data");
        }

        SessionSnapshot snapshot = new SessionSnapshot(
                turns,
                session.getLoadedContextClassNames(),
                session.getSystemPrompt(),
                Instant.now().toString(),
                session.getTotalInputTokens(),
                session.getTotalOutputTokens(),
                session.getTotalCacheCreationTokens(),
                session.getTotalCacheReadTokens()
        );
        saveSnapshot(sessionName, snapshot);
    }

    /**
     * Saves a pre-created SessionSnapshot (used for testing or manual restores).
     */
    public void saveSnapshot(String sessionName, SessionSnapshot snapshot) throws IOException {
        Path sessionDir = storageRoot.resolve(sessionName);
        Files.createDirectories(sessionDir);

        Path manifestPath = sessionDir.resolve("manifest.json");
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
        Files.writeString(manifestPath, json);
    }

    public SessionSnapshot loadSession(String sessionName) throws IOException {
        Path manifestPath = storageRoot.resolve(sessionName).resolve("manifest.json");

        if (!Files.exists(manifestPath)) {
            throw new IOException("Session file not found: " + manifestPath);
        }

        String json = Files.readString(manifestPath);
        return objectMapper.readValue(json, SessionSnapshot.class);
    }

    public List<String> listSessions() throws IOException {
        if (!Files.isDirectory(storageRoot)) return List.of();

        List<String> sessions = new ArrayList<>();
        try (var stream = Files.list(storageRoot)) {
            stream.filter(Files::isDirectory)
                    .forEach(p -> sessions.add(p.getFileName().toString()));
        }
        return sessions;
    }

    public void deleteSession(String sessionName) throws IOException {
        Path sessionDir = storageRoot.resolve(sessionName);
        if (Files.exists(sessionDir)) {
            try (var walk = Files.walk(sessionDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.delete(path); } catch (IOException ignored) {}
                        });
            }
        }
    }

    public Path getStorageRoot() {
        return storageRoot;
    }
}
