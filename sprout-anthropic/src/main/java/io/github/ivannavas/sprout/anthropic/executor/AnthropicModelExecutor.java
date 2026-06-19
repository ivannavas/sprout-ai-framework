package io.github.ivannavas.sprout.anthropic.executor;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ModelExecutor} for Anthropic's Messages API, registered under the bean name
 * {@code anthropic}. Configure {@code anthropic.api.key} and {@code anthropic.model.name} (and
 * optionally {@code anthropic.max.tokens}, {@code anthropic.timeout.seconds} and
 * {@code anthropic.api.url}). The API key also resolves from the {@code ANTHROPIC_API_KEY}
 * environment variable.
 */
@Model("anthropic")
public class AnthropicModelExecutor extends ModelExecutor {

    @Value("${anthropic.api.key}")
    protected String apiKey;

    @Value("${anthropic.model.name}")
    protected String modelName;

    @Value("${anthropic.max.tokens:4096}")
    protected int maxTokens;

    @Value("${anthropic.timeout.seconds:60}")
    protected int requestTimeoutSeconds;

    @Value("${anthropic.api.url:https://api.anthropic.com/v1/messages}")
    protected String apiUrl;

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    protected HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ModelResponse chat(ModelRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Anthropic API key is not configured. Set 'anthropic.api.key' "
                    + "(for example via the ANTHROPIC_API_KEY environment variable).");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("max_tokens", maxTokens);

        List<Message> systemMessages = request.messages().stream()
                .filter(m -> m.role() == Role.SYSTEM)
                .toList();
        List<Message> conversationMessages = request.messages().stream()
                .filter(m -> m.role() != Role.SYSTEM)
                .toList();

        if (!systemMessages.isEmpty()) {
            body.put("system", systemMessages.stream()
                    .map(Message::content)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b));
        }

        body.put("messages", conversationMessages.stream().map(this::toMessageMap).toList());

        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::toToolMap).toList());
        }

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(objectMapper.readTree(response.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Anthropic chat request failed", e);
        }
    }

    private Map<String, Object> toToolMap(ToolDefinition tool) {
        try {
            return Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "input_schema", objectMapper.readTree(tool.parametersJson())
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tool definition: " + tool.name(), e);
        }
    }

    private Map<String, Object> toMessageMap(Message message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", message.role() == Role.TOOL ? "user" : message.role().name().toLowerCase());

        switch (message.role()) {
            case USER -> map.put("content", message.content());
            case ASSISTANT -> {
                List<Map<String, Object>> contentBlocks = new ArrayList<>();
                if (message.content() != null) {
                    contentBlocks.add(Map.of("type", "text", "text", message.content()));
                }
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    for (ToolCall tc : message.toolCalls()) {
                        try {
                            Map<String, Object> block = new LinkedHashMap<>();
                            block.put("type", "tool_use");
                            block.put("id", tc.id());
                            block.put("name", tc.name());
                            block.put("input", objectMapper.readTree(tc.argumentsJson()));
                            contentBlocks.add(block);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to serialize tool call arguments for: " + tc.name(), e);
                        }
                    }
                }
                map.put("content", contentBlocks);
            }
            case TOOL -> {
                ToolResult result = message.toolResult();
                map.put("content", List.of(Map.of(
                        "type", "tool_result",
                        "tool_use_id", result.toolCallId(),
                        "content", result.content()
                )));
            }
        }
        return map;
    }

    private ModelResponse parseResponse(JsonNode root) {
        String text = null;
        List<ToolCall> toolCalls = new ArrayList<>();

        for (JsonNode block : root.get("content")) {
            String type = block.get("type").asText();
            if ("text".equals(type)) {
                text = block.get("text").asText();
            } else if ("tool_use".equals(type)) {
                try {
                    String argumentsJson = objectMapper.writeValueAsString(block.get("input"));
                    toolCalls.add(new ToolCall(block.get("id").asText(), block.get("name").asText(), argumentsJson));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to deserialize tool_use input", e);
                }
            }
        }

        Message message = new Message(Role.ASSISTANT, text, toolCalls, null);

        TokenUsage usage = TokenUsage.ZERO;
        if (root.has("usage") && !root.get("usage").isNull()) {
            JsonNode u = root.get("usage");
            usage = new TokenUsage(u.get("input_tokens").asLong(), u.get("output_tokens").asLong());
        }

        FinishReason finishReason = switch (root.get("stop_reason").asText()) {
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.MAX_TOKENS;
            default -> FinishReason.STOP;
        };

        return new ModelResponse(message, usage, finishReason);
    }
}
