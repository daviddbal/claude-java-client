package net.balsoftware.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Diagnostic utility for understanding prompt caching behavior.
 *
 * PROMPT CACHING ANALYSIS:
 * ========================
 *
 * Anthropic's Prompt Caching works as follows:
 *
 * 1. CACHE WRITES (cache_creation_input_tokens):
 *    - Occurs on FIRST request with a large enough system prompt (typically >1024 tokens)
 *    - The system prompt gets cached by the API
 *    - Full cost: full input tokens charged (no discount)
 *
 * 2. CACHE HITS (cache_read_input_tokens):
 *    - Occurs on SUBSEQUENT requests with identical system prompt
 *    - Cache read tokens are charged at 10% of regular input token cost (from Anthropic docs)
 *    - Requires: same model, same system prompt, within cache TTL (~5 minutes)
 *    - Only conversation turns (user/assistant) count as "new" input
 *
 * 3. WHY YOU MIGHT NOT SEE CACHE READS:
 *    a) System prompt is too small (<~1024 tokens) - API doesn't cache small prompts
 *    b) Time gap between requests > cache TTL (~5 minutes default)
 *    c) Different system prompts between requests (context changed)
 *    d) Different model between requests
 *    e) API isn't receiving the beta header properly
 *    f) Using test/mock clients that don't simulate caching
 *
 * HOW THIS IMPLEMENTATION HANDLES IT:
 * ===================================
 *
 * OKHttpClaudeClient:
 *    - Adds header: "anthropic-beta: prompt-caching-2024-07-31"
 *    - Sets "cache_control": {"type": "ephemeral"} on system prompt
 *    - System prompt is wrapped in array format as per Anthropic docs
 *
 * ClaudeSession:
 *    - MAINTAINS SAME SYSTEM PROMPT across requests (via contextManager caching)
 *    - REBUILDS SYSTEM PROMPT on restore by reloading the same context
 *    - Session persistence saves the context class names and turns for restoration
 *
 * BEST PRACTICES:
 * ===============
 * 1. Load context ONCE and reuse (don't reload with same classes)
 * 2. Make multiple requests in quick succession (<5 min)
 * 3. Ensure system prompt is >1KB (easy with loaded source files)
 * 4. Persist with SessionPersistence.saveSession() and resume with
 *    ClaudeSession.restore() to rebuild the same prompt and reuse cache benefits
 * 5. Verify you're hitting the real Claude API (not mocks)
 * 6. Check response headers: look for cache_read_input_tokens > 0
 */
public class PromptCacheDiagnostics {

    /**
     * Runs diagnostic tests on prompt caching.
     */
    public static void runDiagnostics(ClaudeSession session) throws IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PROMPT CACHE DIAGNOSTICS");
        System.out.println("=".repeat(60));

        // Check 1: System prompt size
        ClaudeRunner runner = session.getRunner();
        if (runner == null) {
            System.out.println("❌ No context loaded. Load context first with session.loadContext()");
            return;
        }

        String systemPrompt = runner.getCachedSystemPrompt();
        int promptLength = systemPrompt.length();
        int estimatedTokens = estimateTokens(promptLength);

        System.out.println("\n1. SYSTEM PROMPT SIZE:");
        System.out.println("   Length: " + promptLength + " characters");
        System.out.println("   Est. tokens: ~" + estimatedTokens);
        if (estimatedTokens < 1024) {
            System.out.println("   ⚠️  WARNING: Prompt may be too small for caching (min ~1024 tokens)");
        } else {
            System.out.println("   ✓ Large enough for caching");
        }

        // Check 2: Context loading
        System.out.println("\n2. CONTEXT SETUP:");
        List<String> contextFiles = session.getLoadedContextFiles();
        if (contextFiles.isEmpty()) {
            System.out.println("   ⚠️  No context files loaded. Load context to enable caching.");
        } else {
            System.out.println("   ✓ Loaded " + contextFiles.size() + " context files");
        }

        // Check 3: Token accumulation
        System.out.println("\n3. TOKEN TRACKING:");
        System.out.println("   " + session.tokenSummary());
        if (session.isCacheHitObserved()) {
            System.out.println("   ✓ Cache hits detected!");
        } else {
            System.out.println("   ⚠️  No cache hits yet.");
            System.out.println("      Possible causes:");
            System.out.println("      - First request (cache miss expected)");
            System.out.println("      - System prompt changed between requests");
            System.out.println("      - Time gap > cache TTL (~5 minutes)");
            System.out.println("      - Using mock client instead of real API");
        }

        // Check 4: Usage recommendations
        System.out.println("\n4. RECOMMENDATIONS:");
        System.out.println("   • Load context early and keep it loaded");
        System.out.println("   • Make subsequent requests within 5 minutes");
        System.out.println("   • Persist with SessionPersistence.saveSession() and resume with");
        System.out.println("     ClaudeSession.restore() to rebuild the same prompt and reuse cache");
        System.out.println("   • Verify you're using real API key (not mock)");
        System.out.println("   • Check that system prompt remains unchanged between requests");

        System.out.println("\n" + "=".repeat(60) + "\n");
    }

    /**
     * Crude token estimation: ~1 token per 4 characters.
     */
    private static int estimateTokens(int characters) {
        return (int) Math.ceil(characters / 4.0);
    }

    /**
     * Simulates cache behavior with multiple requests.
     */
    public static void simulateCachingScenario(ClaudeSession session, String context) throws IOException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("CACHING SCENARIO TEST");
        System.out.println("(Make requests quickly to test cache)");
        System.out.println("=".repeat(60));

        System.out.println("\nREQUEST 1 (Cache Miss Expected):");
        ClaudeStructuredResponseWithTokens resp1 = session.ask("Hello, cache test 1");
        printCacheInfo(resp1);

        System.out.println("\nREQUEST 2 (Cache Hit Expected if within 5 min):");
        ClaudeStructuredResponseWithTokens resp2 = session.ask("Hello, cache test 2");
        printCacheInfo(resp2);

        System.out.println("\nREQUEST 3 (Cache Hit Expected if within 5 min):");
        ClaudeStructuredResponseWithTokens resp3 = session.ask("Hello, cache test 3");
        printCacheInfo(resp3);

        System.out.println("\nCACHE STATUS AFTER 3 REQUESTS:");
        System.out.println("   " + session.tokenSummary());

        if (resp2.cacheReadTokens() > 0 || resp3.cacheReadTokens() > 0) {
            System.out.println("\n✓ CACHING IS WORKING!");
        } else {
            System.out.println("\n❌ CACHING NOT DETECTED");
            System.out.println("   Check your system prompt size and API connection");
        }

        System.out.println("\n" + "=".repeat(60) + "\n");
    }

    private static void printCacheInfo(ClaudeStructuredResponseWithTokens resp) {
        System.out.println("   Input tokens: " + resp.inputTokens());
        System.out.println("   Cache write: " + resp.cacheCreationTokens());
        System.out.println("   Cache read: " + resp.cacheReadTokens());
        if (resp.cacheReadTokens() > 0) {
            System.out.println("   ✓ CACHE HIT");
        } else if (resp.cacheCreationTokens() > 0) {
            System.out.println("   ⚠️  CACHE MISS (cache being written)");
        } else {
            System.out.println("   ⚠️  NO CACHE");
        }
    }
}
