package io.github.ivannavas.sprout.openai.executor;


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
 * {@link ModelExecutor} for OpenAI's Chat Completions API, registered under the bean name
 * {@code openai}. Configure {@code openai.api.key} (optionally {@code openai.timeout.seconds} and
 * {@code openai.api.url}); the API key also resolves from the {@code OPENAI_API_KEY} environment
 * variable.
 *
 * <p>The model can be chosen per call via {@link #chat(String, ModelRequest)} /
 * {@code chatStream(String, ...)}. Configuring {@code openai.model.name} is optional: it only
 * supplies the default used by the no-arg {@link #chat(ModelRequest)} overload, so it is not
 * required when every call names its own model.
 */
@Model("openai")
public class OpenaiModelExecutor extends ModelExecutor {

    @Value("${openai.api.key}")
    protected String apiKey;

    @Value("${openai.model.name:}")
    protected String modelName;

    @Value("${openai.timeout.seconds:60}")
    protected int requestTimeoutSeconds;

    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    protected String apiUrl;

    protected HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ModelResponse chat(ModelRequest request) {
        return chat(modelName, request);
    }

    @Override
    public ModelResponse chat(String modelName, ModelRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is not configured. Set 'openai.api.key' "
                    + "(for example via the OPENAI_API_KEY environment variable).");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("No OpenAI model specified. Pass a model name per call "
                    + "(chat/chatStream overloads that take a model name) or configure a default via "
                    + "'openai.model.name'.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", request.messages().stream().map(this::toMessageMap).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::toToolMap).toList());
        }
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + response.body());
            }

            return parseResponse(objectMapper.readTree(response.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenAI chat request failed", e);
        }
    }

    private Map<String, Object> toToolMap(ToolDefinition tool) {
        try {
            return Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", tool.name(),
                            "description", tool.description(),
                            "parameters", objectMapper.readTree(tool.parametersJson())
                    )
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tool definition: " + tool.name(), e);
        }
    }

    private Map<String, Object> toMessageMap(Message message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", message.role().name().toLowerCase());
        switch (message.role()) {
            case SYSTEM, USER -> map.put("content", message.content());
            case ASSISTANT -> {
                if (message.content() != null) map.put("content", message.content());
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    map.put("tool_calls", message.toolCalls().stream()
                            .map(tc -> Map.of(
                                    "id", tc.id(),
                                    "type", "function",
                                    "function", Map.of("name", tc.name(), "arguments", tc.argumentsJson())
                            ))
                            .toList());
                }
            }
            case TOOL -> {
                ToolResult result = message.toolResult();
                map.put("tool_call_id", result.toolCallId());
                map.put("content", result.content());
            }
        }
        return map;
    }

    private ModelResponse parseResponse(JsonNode root) {
        JsonNode choice = root.get("choices").get(0);
        JsonNode msg = choice.get("message");

        String content = msg.has("content") && !msg.get("content").isNull()
                ? msg.get("content").asText() : null;

        List<ToolCall> toolCalls = List.of();
        if (msg.has("tool_calls") && !msg.get("tool_calls").isNull()) {
            toolCalls = new ArrayList<>();
            for (JsonNode tc : msg.get("tool_calls")) {
                JsonNode fn = tc.get("function");
                toolCalls.add(new ToolCall(tc.get("id").asText(), fn.get("name").asText(), fn.get("arguments").asText()));
            }
        }

        Message message = new Message(Role.ASSISTANT, content, toolCalls, null);

        TokenUsage usage = TokenUsage.ZERO;
        if (root.has("usage") && !root.get("usage").isNull()) {
            JsonNode u = root.get("usage");
            usage = new TokenUsage(u.get("prompt_tokens").asLong(), u.get("completion_tokens").asLong());
        }

        FinishReason finishReason = switch (choice.get("finish_reason").asText()) {
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.MAX_TOKENS;
            default -> FinishReason.STOP;
        };

        return new ModelResponse(message, usage, finishReason);
    }
}
