package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

public class ClaudeClient {
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final int maxTokens;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClaudeClient(String apiKey, int maxTokens) {
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public ClaudeClient(String apiKey) {
        this(apiKey, 4096);
    }

    /**
     * Sends a conversation to Claude. System messages are extracted and sent
     * in the top-level "system" field. User/assistant turns go in "messages".
     *
     * @return RawResponse containing the text content and token usage
     */
    public RawResponse send(String model, List<ClaudeMessage> messages) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        StringBuilder systemText = new StringBuilder();
        ArrayNode messagesArray = objectMapper.createArrayNode();

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
            body.put("system", systemText.toString());
        }
        body.set("messages", messagesArray);

        String requestJson = objectMapper.writeValueAsString(body);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
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

        int inputTokens  = root.path("usage").path("input_tokens").asInt(0);
        int outputTokens = root.path("usage").path("output_tokens").asInt(0);

        return new RawResponse(text, inputTokens, outputTokens);
    }

    /** Intermediate carrier for the API response before parsing into ClaudeResponse. */
    public record RawResponse(String text, int inputTokens, int outputTokens) {}
}