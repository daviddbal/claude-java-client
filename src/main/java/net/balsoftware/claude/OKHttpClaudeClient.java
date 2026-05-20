package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class OKHttpClaudeClient implements ClaudeClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String BETA_PROMPT_CACHE = "prompt-caching-2024-07-31";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Default max output tokens when not specified. */
    public static final int DEFAULT_MAX_TOKENS = 4096 * 4;

    // Number of retries (in addition to the first attempt) for transient API failures.
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 8_000;

    // Dumps every request/response (including the full source-code system prompt and
    // conversation) to disk. Off by default; opt in with CLAUDE_COLLECT_RAW=true.
    private static final boolean COLLECT_CLAUDE_RAW =
            Boolean.parseBoolean(System.getenv().getOrDefault("CLAUDE_COLLECT_RAW", "false"));

    // OkHttp is designed to be shared: one instance backs all clients so connection and
    // thread pools are reused instead of leaked per system-prompt change.
    private static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final String apiKey;
    private final int maxTokens;
    private final String systemPrompt;
    private final ObjectMapper objectMapper;

    public OKHttpClaudeClient(String apiKey, int maxTokens, String systemPrompt) {
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public OKHttpClaudeClient(String apiKey, String systemPrompt) {
        this(apiKey, DEFAULT_MAX_TOKENS, systemPrompt);
    }

    public RawResponse send(String model, List<ClaudeMessage> conversationTurns) throws IOException {
        String requestJson = buildRequestBody(model, conversationTurns, false);
        String requestId = newRequestId();
        if (COLLECT_CLAUDE_RAW) {
            saveRaw("collected-claude-requests", "request", requestId, requestJson);
        }

        return executeWithRetry(newRequest(requestJson), response -> {
            String responseStr = response.body() != null ? response.body().string() : "";
            if (COLLECT_CLAUDE_RAW) {
                saveRaw("collected-claude-responses", "response", requestId, responseStr);
            }
            return parseResponse(responseStr);
        });
    }

    @Override
    public RawResponse sendStreaming(String model, List<ClaudeMessage> conversationTurns,
                                     Consumer<String> onTextDelta) throws IOException {
        String requestJson = buildRequestBody(model, conversationTurns, true);
        String requestId = newRequestId();
        if (COLLECT_CLAUDE_RAW) {
            saveRaw("collected-claude-requests", "request", requestId, requestJson);
        }

        return executeWithRetry(newRequest(requestJson), response -> readStream(response, onTextDelta, requestId));
    }

    private String buildRequestBody(String model, List<ClaudeMessage> conversationTurns, boolean stream)
            throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (stream) {
            body.put("stream", true);
        }

        if (!systemPrompt.isBlank()) {
            ArrayNode systemArray = objectMapper.createArrayNode();

            ObjectNode systemBlock = objectMapper.createObjectNode();
            systemBlock.put("type", "text");
            systemBlock.put("text", systemPrompt);

            ObjectNode cacheControl = objectMapper.createObjectNode();
            cacheControl.put("type", "ephemeral");
            systemBlock.set("cache_control", cacheControl);

            systemArray.add(systemBlock);
            body.set("system", systemArray);
        }

        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (ClaudeMessage msg : conversationTurns) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", msg.role().name().toLowerCase());
            node.put("content", msg.content());
            messagesArray.add(node);
        }
        body.set("messages", messagesArray);

        return objectMapper.writeValueAsString(body);
    }

    private static String newRequestId() {
        return System.currentTimeMillis() + "-" + (int) (Math.random() * 100_000);
    }

    private Request newRequest(String requestJson) {
        return new Request.Builder()
                .url(apiUrl())
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", BETA_PROMPT_CACHE)
                .header("content-type", "application/json")
                .post(RequestBody.create(requestJson, JSON))
                .build();
    }

    @FunctionalInterface
    private interface ResponseHandler {
        RawResponse handle(Response response) throws IOException;
    }

    /** Runs a request with retry/backoff, delegating success handling to {@code handler}. */
    private RawResponse executeWithRetry(Request request, ResponseHandler handler) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Response response;
            try {
                response = SHARED_HTTP_CLIENT.newCall(request).execute();
            } catch (IOException networkError) {
                // Connection/read failures are transient; retry with backoff.
                if (attempt < MAX_RETRIES) {
                    sleep(backoffMillis(attempt));
                    continue;
                }
                throw networkError;
            }

            try (response) {
                if (response.isSuccessful()) {
                    return handler.handle(response);
                }

                int code = response.code();
                String errorBody = response.body() != null ? response.body().string() : "(no body)";

                if (isRetryable(code) && attempt < MAX_RETRIES) {
                    sleep(retryDelayMillis(response, attempt));
                    continue;
                }

                throw new IOException("Claude API error " + code + ": " + errorBody);
            }
        }
    }

    /**
     * Reads an Anthropic SSE stream: invokes {@code onTextDelta} for each text chunk and
     * accumulates the full text plus token usage into a {@link RawResponse}.
     */
    private RawResponse readStream(Response response, Consumer<String> onTextDelta, String requestId)
            throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("Empty streaming response body");
        }

        StringBuilder fullText = new StringBuilder();
        int inputTokens = 0;
        int outputTokens = 0;
        int cacheCreationTokens = 0;
        int cacheReadTokens = 0;

        try (BufferedReader reader = new BufferedReader(body.charStream())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue; // skip "event:" lines and blank separators
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    continue;
                }

                JsonNode event = objectMapper.readTree(data);
                switch (event.path("type").asText("")) {
                    case "message_start" -> {
                        JsonNode usage = event.path("message").path("usage");
                        inputTokens = usage.path("input_tokens").asInt(inputTokens);
                        cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt(cacheCreationTokens);
                        cacheReadTokens = usage.path("cache_read_input_tokens").asInt(cacheReadTokens);
                        outputTokens = usage.path("output_tokens").asInt(outputTokens);
                    }
                    case "content_block_delta" -> {
                        JsonNode delta = event.path("delta");
                        if ("text_delta".equals(delta.path("type").asText())) {
                            String text = delta.path("text").asText("");
                            if (!text.isEmpty()) {
                                fullText.append(text);
                                if (onTextDelta != null) {
                                    onTextDelta.accept(text);
                                }
                            }
                        }
                    }
                    case "message_delta" -> outputTokens = event.path("usage").path("output_tokens").asInt(outputTokens);
                    case "error" -> throw new IOException("Claude API stream error: " + event.path("error"));
                    default -> { /* ping, content_block_start/stop, message_stop: ignore */ }
                }
            }
        }

        String text = fullText.toString();
        if (COLLECT_CLAUDE_RAW) {
            saveRaw("collected-claude-responses", "response", requestId, text);
        }
        return new RawResponse(text, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens);
    }

    /** The endpoint to POST to. Overridable so tests can point at an in-process server. */
    String apiUrl() {
        return API_URL;
    }

    /** Transient HTTP statuses that are worth retrying. */
    private static boolean isRetryable(int code) {
        return code == 429   // rate limited
                || code == 500   // transient API error
                || code == 502
                || code == 503
                || code == 504
                || code == 529;  // overloaded
    }

    /** Honors a Retry-After header (seconds) when present, otherwise uses exponential backoff. */
    private static long retryDelayMillis(Response response, int attempt) {
        String retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // not a plain seconds value — fall through to backoff
            }
        }
        return backoffMillis(attempt);
    }

    private static long backoffMillis(int attempt) {
        return Math.min(BASE_BACKOFF_MS << attempt, MAX_BACKOFF_MS);
    }

    private static void sleep(long millis) throws IOException {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while backing off before retry", e);
        }
    }

    private static void saveRaw(String dirName, String prefix, String requestId, String content) {
        try {
            File outDir = new File(dirName);
            if (!outDir.exists()) outDir.mkdirs();

            File out = new File(outDir, prefix + "-" + requestId + ".json");
            Files.createDirectories(out.toPath().getParent());

            try (FileWriter fw = new FileWriter(out)) {
                fw.write(content);
            }
        } catch (IOException ioe) {
            System.err.println("Could not save " + prefix + ": " + ioe);
        }
    }

    private RawResponse parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        String text = "";
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text = block.path("text").asText("");
                    break;
                }
            }
        }

        JsonNode usage = root.path("usage");
        return new RawResponse(
                text,
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                usage.path("cache_creation_input_tokens").asInt(0),
                usage.path("cache_read_input_tokens").asInt(0)
        );
    }

    public record RawResponse(
            String text,
            int inputTokens,
            int outputTokens,
            int cacheCreationTokens,
            int cacheReadTokens
    ) {}
}