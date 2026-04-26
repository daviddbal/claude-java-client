package net.balsoftware.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ClaudeClient {
    private static final String API_URL           = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String BETA_PROMPT_CACHE = "prompt-caching-2024-07-31";
    private static final MediaType JSON           = MediaType.get("application/json; charset=utf-8");
    private static final boolean COLLECT_CLAUDE_RAW = true;

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
        this(apiKey, 4096*4);
    }

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
                .header("anthropic-beta", BETA_PROMPT_CACHE)
                .header("content-type", "application/json")
                .post(RequestBody.create(requestJson, JSON))
                .build();

        System.out.println("[DEBUG] Sending HTTP request...");
        System.out.flush();

        try (Response response = httpClient.newCall(request).execute()) {
            System.out.println("[DEBUG] HTTP response received (headers only)");
            System.out.flush();
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Claude API error " + response.code() + ": " + errorBody);
            }
            String responseStr = response.body() != null ? response.body().string() : "";
            System.out.println("[DEBUG] Finished reading response body");
            System.out.flush();
            System.out.println("responseStr=" + responseStr.substring(0, 100));
            if (COLLECT_CLAUDE_RAW) {
                saveRawClaudeResponse(responseStr);
            }
            return parseResponse(responseStr);
        }
    }

    private static void saveRawClaudeResponse(String responseStr) {
        try {
            String dir = "collected-claude-responses";
            File outDir = new File(dir);
            if (!outDir.exists()) outDir.mkdirs();
            String filename = "response-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 100_000) + ".txt";
            File out = new File(outDir, filename);
            Files.createDirectories(out.toPath().getParent());
            try (FileWriter fw = new FileWriter(out)) {
                fw.write(responseStr);
            }
            System.out.println("Saved raw Claude response to: " + out.getAbsolutePath());
        } catch (IOException ioe) {
            System.err.println("Could not save raw Claude response: " + ioe);
        }
    }

    private RawResponse parseResponse(String responseJson) throws IOException {
        if (responseJson == null || responseJson.trim().isEmpty()) {
            System.err.println("Claude API returned empty response.");
            return new RawResponse("[Claude API returned no content]", 0, 0, 0, 0);
        }
        try {
            JsonNode root = objectMapper.readTree(responseJson);

            String text = null;
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        text = block.path("text").asText();
                        if (text == null) text = "";
                        break;
                    }
                }
            }
            if (text == null) {
                System.err.println("No text content found in Claude response; raw JSON:");
                System.err.println(responseJson.substring(0, Math.min(2000, responseJson.length())));
                return new RawResponse("[Claude API returned unrecognized content format]", 0, 0, 0, 0);
            }

            JsonNode usage = root.path("usage");
            int inputTokens         = usage.path("input_tokens").asInt(0);
            int outputTokens        = usage.path("output_tokens").asInt(0);
            int cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt(0);
            int cacheReadTokens     = usage.path("cache_read_input_tokens").asInt(0);

            return new RawResponse(text, inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens);

        } catch (Exception e) {
            System.err.println("=== RAW CLAUDE RESPONSE (truncated) ===");
            if (responseJson != null)
                System.err.println(responseJson.substring(0, Math.min(2000, responseJson.length())));
            System.err.println("=== END RESPONSE ===");
            e.printStackTrace(System.err);
            throw new IOException("Failed to parse Claude response: " + e.getMessage(), e);
        }
    }

    public record RawResponse(
            String text,
            int inputTokens,
            int outputTokens,
            int cacheCreationTokens,
            int cacheReadTokens
    ) {}
}