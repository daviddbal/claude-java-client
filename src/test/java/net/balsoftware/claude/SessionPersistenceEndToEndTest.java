package net.balsoftware.claude;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("🔍 MANUAL INSPECT + FULL E2E - ~$0.015")
class SessionPersistenceEndToEndTest {

    @Test
    void endToEndStorageAndRecallInspect(@TempDir Path tempDir) throws IOException {
        String apiKey = validateApiKey();
        System.out.println("📁 Temp dir (inspect here): " + tempDir.toAbsolutePath());

        SessionPersistence persistence = new SessionPersistence(tempDir);

        // ===== 1. BUILD REAL CONVERSATION =====
        System.out.println("\n🔄 1. Building real conversation...");
        ClaudeSession original = buildRealConversation(apiKey, tempDir);

        int originalTurns = original.getTurnCount();
        int originalTokens = original.getTotalInputTokens();
        String originalSystemPrompt = original.getSystemPrompt();
        List<String> originalContext = original.getLoadedContextClassNames();

        System.out.println("✅ Original: " + originalTurns + " turns, " +
                originalTokens + " input tokens, context=" + originalContext);

        // ===== 2. SAVE =====
        System.out.println("\n🔄 2. Saving to disk...");
        String sessionName = "java-session-inspect";
        persistence.saveSession(sessionName, original);

        Path manifestPath = tempDir.resolve(sessionName).resolve("manifest.json");
        System.out.println("💾 Saved: " + manifestPath.toAbsolutePath());

        // ===== 3. LOAD SNAPSHOT & FULL VERIFICATION =====
        System.out.println("\n🔄 3. Loading & verifying snapshot...");
        SessionSnapshot snapshot = persistence.loadSession(sessionName);
        assertFullSnapshot(original, snapshot);
        System.out.println("✅ Snapshot perfect: " + snapshot.turns().size() + " turns restored");

        // ===== 4. INSPECTION INFO =====
        System.out.println("\n📁 INSPECT FILES (they stay open):");
        System.out.println("   Directory:  " + tempDir.resolve(sessionName));
        System.out.println("   Manifest:   " + manifestPath);
        System.out.println("   Quick view: cat " + manifestPath + " | jq .turns[].userMessage");

        // ===== 5. RESTORE TO NEW SESSION =====
        System.out.println("\n🔄 4. Restoring full session (context + turns + tokens)...");
        ClaudeSession restored = ClaudeSession.builder()
                .apiKey(apiKey)
                .model(ClaudeModel.HAIKU_5.id())
                .maxTokens(2048)
                .build();

        List<Class<?>> restoredContext = snapshot.loadedContextClassNames().stream()
                .map(this::loadClass)
                .collect(Collectors.toList());
        restored.restore(snapshot, restoredContext);

        // The restore must rehydrate context, conversation turns, and token totals.
        assertEquals(originalContext, restored.getLoadedContextClassNames());
        assertEquals(originalTurns, restored.getTurnCount(), "all turns should be restored");
        assertEquals(original.getConversationHistory(), restored.getConversationHistory(),
                "restored conversation history should match the original");
        assertEquals(originalTokens, restored.getTotalInputTokens(), "token totals should be restored");
        System.out.println("✅ Restored: " + restored.getTurnCount() + " turns, "
                + restored.getTotalInputTokens() + " input tokens, context="
                + restored.getLoadedContextClassNames());

        // ===== 6. PROVE RECALL/CONTINUATION =====
        // This only works because restore() replays the prior turns to the API —
        // the prompt deliberately avoids the word "Java" so a real recall is required.
        System.out.println("\n🔄 5. Testing conversation recall...");
        ClaudeStructuredResponseWithTokens followUp = restored.ask(
                "Based on the language we just discussed, recommend a microservices stack"
        );

        String recallResponse = followUp.structured().description();
        assertTrue(recallResponse.length() > 100, "Should generate meaningful response");
        assertTrue(recallResponse.toLowerCase().contains("java"),
                "Should reference Java from the restored conversation, not the prompt");
        assertEquals(originalTurns + 1, restored.getTurnCount(),
                "follow-up should append to the restored history");

        System.out.println("✅ RECALL WORKS:");
        System.out.println("   " + recallResponse.substring(0, 200) + "...");

        System.out.println("\n🎉 FULL SUCCESS!");
        System.out.println("✅ Storage → JSON → Snapshot → Context restore → Conversation recall");
        System.out.println("📁 Files ready for inspection: " + manifestPath);
    }

    private ClaudeSession buildRealConversation(String apiKey, Path tempDir) throws IOException {
        ClaudeSession session = ClaudeSession.builder()
                .apiKey(apiKey)
                .model(ClaudeModel.HAIKU_5.id())
                .maxTokens(2048)
                .build();

        session.loadContext(List.of());
        session.ask("What is Java?");
        session.ask("Java history highlights?");
        session.ask("Java 21+ features for microservices?");

        return session;
    }

    private void assertFullSnapshot(ClaudeSession original, SessionSnapshot snapshot) {
        assertEquals(original.getConversationTurns().size(), snapshot.turns().size());
        assertEquals(original.getLoadedContextClassNames(), snapshot.loadedContextClassNames());
        assertEquals(original.getSystemPrompt(), snapshot.systemPrompt());
        assertEquals(original.getTotalInputTokens(), snapshot.totalInputTokens());
        assertEquals(original.getTotalOutputTokens(), snapshot.totalOutputTokens());
        assertEquals(original.getTotalCacheCreationTokens(), snapshot.totalCacheCreationTokens());
        assertEquals(original.getTotalCacheReadTokens(), snapshot.totalCacheReadTokens());

        List<String> originalQuestions = original.getConversationTurns().stream()
                .map(SerializableTurn::getUserMessage).toList();
        List<String> snapshotQuestions = snapshot.turns().stream()
                .map(SerializableTurn::getUserMessage).toList();
        assertEquals(originalQuestions, snapshotQuestions);
    }

    private String validateApiKey() {
        String apiKey = EnvConfig.getClaudeApiKey();
        if (apiKey == null || apiKey.isBlank() || !apiKey.startsWith("sk-ant-")) {
            fail("Set ANTHROPIC_API_KEY from https://console.anthropic.com/settings/keys");
        }
        return apiKey;
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}