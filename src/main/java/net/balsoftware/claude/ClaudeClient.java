package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ClaudeClient {
    private static final String API_URL           = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String BETA_PROMPT_CACHE = "prompt-caching-2024-07-31";
    private static final MediaType JSON           = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final int maxTokens;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClaudeClient(String apiKey, int maxTokens) {
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public ClaudeClient(String apiKey) {
        this(apiKey, 4096);
    }

    /**
     * Sends a conversation to Claude with prompt caching enabled.
     *
     * <p>The system prompt is sent as a content-block array with a
     * {@code cache_control: {type: ephemeral}} marker on the last block.
     * Anthropic will cache everything up to that breakpoint so subsequent
     * requests with the same system prompt are billed at ~10% of normal
     * input-token cost.
     *
     * @return {@link RawResponse} containing text, normal token counts, and
     *         cache-specific token counts.
     */
    public RawResponse send(String model, List<ClaudeMessage> messages) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        StringBuilder systemText = new StringBuilder();
        ArrayNode messagesArray  = objectMapper.createArrayNode();

        for (ClaudeMessage msg : messages) {
            if (msg.role() == ClaudeRole.SYSTEM) {
                if (!systemText.isEmpty()) systemText.append("\n");
                systemText.append(msg.content());
            } else {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("role", msg.role().name().toLowerCase());
                node.put("content", msg.content());
                messagesArray.add(node);
            }
        }

        if (!systemText.isEmpty()) {
            // Prompt caching requires the system field to be an array of
            // content blocks. The cache breakpoint is placed on the last
            // (and only) block via cache_control.
            ArrayNode systemArray  = objectMapper.createArrayNode();
            ObjectNode systemBlock = objectMapper.createObjectNode();
            systemBlock.put("type", "text");
            systemBlock.put("text", systemText.toString());

            ObjectNode cacheControl = objectMapper.createObjectNode();
            cacheControl.put("type", "ephemeral");
            systemBlock.set("cache_control", cacheControl);

            systemArray.add(systemBlock);
            body.set("system", systemArray);
        }

        body.set("messages", messagesArray);

        String requestJson = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", BETA_PROMPT_CACHE)   // enables prompt caching
                .header("content-type", "application/json")
                .post(RequestBody.create(requestJson, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Claude API error " + response.code() + ": " + errorBody);
            }
            return parseResponse(response.body().string());
        }
    }

    private RawResponse parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);

        String text = null;
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text = block.path("text").asText();
                    break;
                }
            }
        }
        if (text == null) {
            throw new IOException("No text content found in Claude response: " + responseJson);
        }

        JsonNode usage = root.path("usage");
        int inputTokens         = usage.path("input_tokens").asInt(0);
        int outputTokens        = usage.path("output_tokens").asInt(0);
        int cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt(0);
        int cacheReadTokens     = usage.path("cache_read_input_tokens").asInt(0);

        return new RawResponse(text, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens);
    }

    /**
     * Carrier for the raw API response.
     *
     * @param cacheCreationTokens tokens written to the prompt cache this turn
     *                            (billed at 125% of normal — one-time cost)
     * @param cacheReadTokens     tokens served from the prompt cache this turn
     *                            (billed at 10% of normal — the saving)
     */
    public record RawResponse(
            String text,
            int inputTokens,
            int outputTokens,
            int cacheCreationTokens,
            int cacheReadTokens
    ) {}
}