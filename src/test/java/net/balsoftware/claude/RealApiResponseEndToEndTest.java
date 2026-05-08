package net.balsoftware.claude;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("🔥 REAL API E2E - All response types + persistence - ~$0.03")
class RealApiResponseEndToEndTest {

    @Test
    void realApiCodeOnlyResponse(@TempDir Path tempDir) throws IOException {
        testRealResponse("Write a Java hello world program. Return ONLY CODE, no explanation.",
                r -> {
                    assertEquals(ClaudeStructuredResponse.Type.code, r.type());
                    assertTrue(r.hasFiles(), "Code must have files");
                    assertTrue(r.files().get(0).content().contains("Hello") ||
                            r.files().get(0).content().contains("hello"), "Must contain hello world");
                },
                tempDir
        );
    }

    @Test
    void realApiExplanationOnlyResponse(@TempDir Path tempDir) throws IOException {
        testRealResponse("Explain quantum computing in one paragraph. No code please.",
                r -> {
                    assertEquals(ClaudeStructuredResponse.Type.explanation, r.type());
                    assertTrue(r.description().length() > 50, "Must have meaningful description");
                    assertFalse(r.hasFiles(), "Explanation should have no files");
                    assertTrue(r.description().toLowerCase().contains("quantum"), "Must answer question");
                },
                tempDir
        );
    }

    @Test
    void realApiCodeWithExplanationResponse(@TempDir Path tempDir) throws IOException {
        testRealResponse("Explain FizzBuzz algorithm then write Java code.",
                r -> {
                    assertEquals(ClaudeStructuredResponse.Type.code_with_explanation, r.type());
                    assertTrue(r.description().length() > 20, "Must explain algorithm");
                    assertTrue(r.hasFiles(), "Must have code files");
                    String code = r.files().get(0).content();
                    assertTrue(code.contains("fizz") || code.contains("Fizz"), "Must implement FizzBuzz");
                },
                tempDir
        );
    }

    @Test
    void realApiMixedResponseTypesPersist(@TempDir Path tempDir) throws IOException {
        String apiKey = EnvConfig.getClaudeApiKey();
        SessionPersistence persistence = new SessionPersistence(tempDir);

        ClaudeSession session = ClaudeSession.builder()
                .apiKey(apiKey)
                .model(ClaudeModel.HAIKU_5.id())
                .maxTokens(1024)
                .build();

        // ✅ loadContext FIRST
        session.loadContext(List.of());

        // Mixed types
        session.ask("Write Java hello world");                    // code
        session.ask("Explain pizza in one sentence");             // explanation
        session.ask("Explain + code FizzBuzz");                   // code+explanation

        // Save
        persistence.saveSession("mixed-real-api", session);

        // Load & verify ALL
        SessionSnapshot snapshot = persistence.loadSession("mixed-real-api");
        assertEquals(3, snapshot.turns().size());

        List<String> questions = snapshot.turns().stream()
                .map(SerializableTurn::getUserMessage)
                .toList();
        assertTrue(questions.contains("Write Java hello world"));
        assertTrue(questions.contains("Explain pizza"));

        assertEquals(session.getTotalInputTokens(), snapshot.totalInputTokens());
        assertEquals(session.getLoadedContextClassNames(), snapshot.loadedContextClassNames());

        System.out.println("✅ MIXED 3 TYPES FULLY PERSISTED!");
        System.out.println("   Files: " + tempDir.resolve("mixed-real-api/manifest.json"));
    }

    private void testRealResponse(String prompt, Consumer<ClaudeStructuredResponse> validator, Path tempDir) throws IOException {
        String apiKey = EnvConfig.getClaudeApiKey();

        ClaudeSession session = ClaudeSession.builder()
                .apiKey(apiKey)
                .model(ClaudeModel.HAIKU_5.id())
                .maxTokens(1024)
                .build();

        // ✅ ALWAYS loadContext first
        session.loadContext(List.of());

        ClaudeStructuredResponseWithTokens response = session.ask(prompt);

        // ✅ Must handle ANY real API response
        ClaudeStructuredResponse r = response.structured();
        assertNotNull(r, "Real API must always parse");
        assertNotNull(r.type(), "Must have valid type");

        validator.accept(r);

        // ✅ Persistence roundtrip
        SessionPersistence persistence = new SessionPersistence(tempDir);
        String testId = prompt.hashCode() + "-real";
        persistence.saveSession(testId, session);
        SessionSnapshot snapshot = persistence.loadSession(testId);
        assertEquals(1, snapshot.turns().size());
        assertEquals(prompt, snapshot.turns().get(0).getUserMessage());

        System.out.println("✅ " + prompt.substring(0, 40) + "...");
        System.out.println("   Type: " + r.type());
        System.out.println("   Files: " + r.files().size());
        System.out.println("   Persisted: " + tempDir.resolve(testId));
    }

    @FunctionalInterface
    interface ResponseValidator {
        void validate(ClaudeStructuredResponse r);
    }
}