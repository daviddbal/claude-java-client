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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OKHttpClaudeClient implements ClaudeClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String BETA_PROMPT_CACHE = "prompt-caching-2024-07-31";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final boolean COLLECT_CLAUDE_RAW = true;

    private final String apiKey;
    private final int maxTokens;
    private final String systemPrompt;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OKHttpClaudeClient(String apiKey, int maxTokens, String systemPrompt) {
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public OKHttpClaudeClient(String apiKey, String systemPrompt) {
        this(apiKey, 4096 * 4, systemPrompt);
    }

    public RawResponse send(String model, List<ClaudeMessage> conversationTurns) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

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

        String requestJson = objectMapper.writeValueAsString(body);
        String requestId = System.currentTimeMillis() + "-" + (int) (Math.random() * 100_000);

        if (COLLECT_CLAUDE_RAW) {
            saveRaw("collected-claude-requests", "request", requestId, requestJson);
        }

        Request request = new Request.Builder()
                .url(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", BETA_PROMPT_CACHE)
                .header("content-type", "application/json")
                .post(RequestBody.create(requestJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Claude API error " + response.code() + ": " + errorBody);
            }

            String responseStr = response.body() != null ? response.body().string() : "";

            if (COLLECT_CLAUDE_RAW) {
                saveRaw("collected-claude-responses", "response", requestId, responseStr);
            }

            RawResponse rawResponse = parseResponse(responseStr);

            return rawResponse;
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