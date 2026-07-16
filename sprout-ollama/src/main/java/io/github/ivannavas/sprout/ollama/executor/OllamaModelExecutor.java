package io.github.ivannavas.sprout.ollama.executor;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.event.ModelRequestEvent;
import io.github.ivannavas.sprout.event.ModelResponseEvent;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link ModelExecutor} for a local Ollama server's {@code /api/chat} endpoint, registered under the
 * bean name {@code ollama}. Ollama runs models locally, so no API key is needed; configure
 * {@code ollama.api.url} (default {@code http://localhost:11434/api/chat}) and optionally
 * {@code ollama.timeout.seconds}.
 *
 * <p>The model can be chosen per call via {@link #chat(String, ModelRequest)} /
 * {@code chatStream(String, ...)}. Configuring {@code ollama.model.name} is optional: it only supplies
 * the default used by the no-arg {@link #chat(ModelRequest)} overload, so it is not required when every
 * call names its own model.
 *
 * <p>{@code chatStream} is a real token-by-token stream: Ollama replies with newline-delimited JSON
 * (one object per line rather than Server-Sent Events), so each {@code message.content} fragment is
 * forwarded to {@link StreamListener#onToken} as it arrives and the full {@link ModelResponse} is
 * assembled for {@link StreamListener#onComplete}.
 */
@Model("ollama")
public class OllamaModelExecutor extends ModelExecutor {

    @Value("${ollama.model.name:}")
    protected String modelName;

    @Value("${ollama.timeout.seconds:120}")
    protected int requestTimeoutSeconds;

    @Value("${ollama.api.url:http://localhost:11434/api/chat}")
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
        checkConfigured(modelName);
        Map<String, Object> body = buildRequestBody(modelName, request);
        body.put("stream", false);
        try {
            HttpResponse<String> response = httpClient.send(
                    buildHttpRequest(body), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Ollama API error " + response.statusCode() + ": " + response.body());
            }
            return parseResponse(objectMapper.readTree(response.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Ollama chat request failed", e);
        }
    }

    @Override
    public void chatStream(ModelRequest request, StreamListener listener) {
        chatStream(modelName, request, listener);
    }

    @Override
    public void chatStream(String modelName, ModelRequest request, StreamListener listener) {
        try {
            streamInternal(modelName, request, listener);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    /**
     * Opens a streaming chat call and drives {@code listener} from Ollama's newline-delimited JSON feed:
     * each {@code message.content} fragment is forwarded via {@link StreamListener#onToken}, any
     * {@code message.tool_calls} are collected, and once the final ({@code done}) object arrives the
     * assembled {@link ModelResponse} is published as a {@link ModelResponseEvent} and delivered through
     * {@link StreamListener#onComplete}.
     */
    private void streamInternal(String modelName, ModelRequest request, StreamListener listener) throws Exception {
        checkConfigured(modelName);

        String executorName = getClass().getSimpleName();
        publish(new ModelRequestEvent(executorName, request));

        Map<String, Object> body = buildRequestBody(modelName, request);
        body.put("stream", true);

        HttpResponse<Stream<String>> response = httpClient.send(
                buildHttpRequest(body), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error;
            try (Stream<String> lines = response.body()) {
                error = lines.collect(Collectors.joining("\n"));
            }
            throw new RuntimeException("Ollama API error " + response.statusCode() + ": " + error);
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        FinishReason finishReason = FinishReason.STOP;

        try (Stream<String> lines = response.body()) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line == null || line.isBlank()) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(line);
                JsonNode message = node.get("message");
                if (message != null) {
                    if (message.has("content") && !message.get("content").isNull()) {
                        String token = message.get("content").asText();
                        if (!token.isEmpty()) {
                            text.append(token);
                            listener.onToken(token);
                        }
                    }
                    if (message.has("tool_calls") && !message.get("tool_calls").isNull()) {
                        toolCalls.addAll(parseToolCalls(message.get("tool_calls")));
                    }
                }

                if (node.path("done").asBoolean(false)) {
                    if (node.has("prompt_eval_count")) inputTokens = node.get("prompt_eval_count").asLong();
                    if (node.has("eval_count")) outputTokens = node.get("eval_count").asLong();
                    finishReason = mapDoneReason(node.path("done_reason").asText("stop"), toolCalls);
                }
            }
        }

        Message message = new Message(Role.ASSISTANT, text.isEmpty() ? null : text.toString(), toolCalls, null);
        ModelResponse modelResponse = new ModelResponse(
                message, new TokenUsage(inputTokens, outputTokens), finishReason);

        publish(new ModelResponseEvent(executorName, modelResponse));
        toolCalls.forEach(listener::onToolCall);
        listener.onComplete(modelResponse);
    }

    private void checkConfigured(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("No Ollama model specified. Pass a model name per call "
                    + "(chat/chatStream overloads that take a model name) or configure a default via "
                    + "'ollama.model.name'.");
        }
    }

    private Map<String, Object> buildRequestBody(String modelName, ModelRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", request.messages().stream().map(this::toMessageMap).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::toToolMap).toList());
        }
        return body;
    }

    private HttpRequest buildHttpRequest(Map<String, Object> body) {
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Ollama request", e);
        }
    }

    private static FinishReason mapDoneReason(String doneReason, List<ToolCall> toolCalls) {
        if (!toolCalls.isEmpty()) {
            return FinishReason.TOOL_CALLS;
        }
        return switch (doneReason) {
            case "length" -> FinishReason.MAX_TOKENS;
            default -> FinishReason.STOP;
        };
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
        map.put("role", message.role() == Role.TOOL ? "tool" : message.role().name().toLowerCase());

        switch (message.role()) {
            case SYSTEM, USER -> map.put("content", message.content());
            case ASSISTANT -> {
                map.put("content", message.content() != null ? message.content() : "");
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    map.put("tool_calls", message.toolCalls().stream()
                            .map(this::toToolCallMap)
                            .toList());
                }
            }
            case TOOL -> map.put("content", message.toolResult().content());
        }
        return map;
    }

    private Map<String, Object> toToolCallMap(ToolCall toolCall) {
        try {
            return Map.of("function", Map.of(
                    "name", toolCall.name(),
                    "arguments", objectMapper.readTree(toolCall.argumentsJson())
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tool call arguments for: " + toolCall.name(), e);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> toolCalls = new ArrayList<>();
        int index = 0;
        for (JsonNode tc : toolCallsNode) {
            JsonNode function = tc.get("function");
            if (function == null) {
                continue;
            }
            try {
                // Ollama returns arguments as a JSON object; Sprout's ToolCall carries them as a JSON string.
                String argumentsJson = objectMapper.writeValueAsString(function.get("arguments"));
                // Ollama does not assign tool-call ids, so synthesise a stable one for correlation.
                String id = tc.has("id") && !tc.get("id").isNull()
                        ? tc.get("id").asText()
                        : "call_" + index;
                toolCalls.add(new ToolCall(id, function.get("name").asText(), argumentsJson));
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize tool call arguments", e);
            }
            index++;
        }
        return toolCalls;
    }

    private ModelResponse parseResponse(JsonNode root) {
        JsonNode msg = root.get("message");

        String content = msg != null && msg.has("content") && !msg.get("content").isNull()
                && !msg.get("content").asText().isEmpty()
                ? msg.get("content").asText() : null;

        List<ToolCall> toolCalls = List.of();
        if (msg != null && msg.has("tool_calls") && !msg.get("tool_calls").isNull()) {
            toolCalls = parseToolCalls(msg.get("tool_calls"));
        }

        Message message = new Message(Role.ASSISTANT, content, toolCalls, null);

        long inputTokens = root.has("prompt_eval_count") ? root.get("prompt_eval_count").asLong() : 0;
        long outputTokens = root.has("eval_count") ? root.get("eval_count").asLong() : 0;
        TokenUsage usage = new TokenUsage(inputTokens, outputTokens);

        FinishReason finishReason = mapDoneReason(root.path("done_reason").asText("stop"), toolCalls);

        return new ModelResponse(message, usage, finishReason);
    }
}
