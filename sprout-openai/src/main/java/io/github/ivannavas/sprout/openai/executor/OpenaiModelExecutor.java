package io.github.ivannavas.sprout.openai.executor;


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
 * {@link ModelExecutor} for OpenAI's Chat Completions API, registered under the bean name
 * {@code openai}. Configure {@code openai.api.key} (optionally {@code openai.timeout.seconds} and
 * {@code openai.api.url}); the API key also resolves from the {@code OPENAI_API_KEY} environment
 * variable.
 *
 * <p>The model can be chosen per call via {@link #chat(String, ModelRequest)} /
 * {@code chatStream(String, ...)}. Configuring {@code openai.model.name} is optional: it only
 * supplies the default used by the no-arg {@link #chat(ModelRequest)} overload, so it is not
 * required when every call names its own model.
 *
 * <p>{@code chatStream} is a real token-by-token stream: it sets {@code stream: true} and consumes
 * the Server-Sent Events response, forwarding each content delta to {@link StreamListener#onToken}
 * as it arrives and assembling the full {@link ModelResponse} for {@link StreamListener#onComplete}.
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
        checkConfigured(modelName);
        Map<String, Object> body = buildRequestBody(modelName, request);
        try {
            HttpResponse<String> response = httpClient.send(
                    buildHttpRequest(body), HttpResponse.BodyHandlers.ofString());
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
     * Opens a streaming Chat Completions call and drives {@code listener} from the SSE feed: each
     * {@code delta.content} chunk is forwarded via {@link StreamListener#onToken}, tool-call fragments
     * are accumulated by index, and once the stream ends the assembled {@link ModelResponse} is
     * published as a {@link ModelResponseEvent} and delivered through {@link StreamListener#onComplete}.
     */
    private void streamInternal(String modelName, ModelRequest request, StreamListener listener) throws Exception {
        checkConfigured(modelName);

        String executorName = getClass().getSimpleName();
        publish(new ModelRequestEvent(executorName, request));

        Map<String, Object> body = buildRequestBody(modelName, request);
        body.put("stream", true);
        // Chat Completions only reports usage on a streamed call when explicitly asked to.
        body.put("stream_options", Map.of("include_usage", true));

        HttpResponse<Stream<String>> response = httpClient.send(
                buildHttpRequest(body), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error;
            try (Stream<String> lines = response.body()) {
                error = lines.collect(Collectors.joining("\n"));
            }
            throw new RuntimeException("OpenAI API error " + response.statusCode() + ": " + error);
        }

        StringBuilder text = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();
        TokenUsage usage = TokenUsage.ZERO;
        FinishReason finishReason = FinishReason.STOP;

        try (Stream<String> lines = response.body()) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line == null || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    if (data.equals("[DONE]")) break;
                    continue;
                }

                JsonNode node = objectMapper.readTree(data);
                if (node.has("usage") && !node.get("usage").isNull()) {
                    JsonNode u = node.get("usage");
                    usage = new TokenUsage(u.get("prompt_tokens").asLong(), u.get("completion_tokens").asLong());
                }

                JsonNode choices = node.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.get("delta");
                if (delta != null) {
                    if (delta.has("content") && !delta.get("content").isNull()) {
                        String token = delta.get("content").asText();
                        if (!token.isEmpty()) {
                            text.append(token);
                            listener.onToken(token);
                        }
                    }
                    if (delta.has("tool_calls") && !delta.get("tool_calls").isNull()) {
                        for (JsonNode tc : delta.get("tool_calls")) {
                            int index = tc.has("index") ? tc.get("index").asInt() : 0;
                            ToolCallBuilder builder = toolCalls.computeIfAbsent(index, k -> new ToolCallBuilder());
                            if (tc.has("id") && !tc.get("id").isNull()) {
                                builder.id = tc.get("id").asText();
                            }
                            JsonNode function = tc.get("function");
                            if (function != null) {
                                if (function.has("name") && !function.get("name").isNull()) {
                                    builder.name = function.get("name").asText();
                                }
                                if (function.has("arguments") && !function.get("arguments").isNull()) {
                                    builder.arguments.append(function.get("arguments").asText());
                                }
                            }
                        }
                    }
                }
                if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                    finishReason = switch (choice.get("finish_reason").asText()) {
                        case "tool_calls" -> FinishReason.TOOL_CALLS;
                        case "length" -> FinishReason.MAX_TOKENS;
                        default -> FinishReason.STOP;
                    };
                }
            }
        }

        List<ToolCall> assembled = toolCalls.values().stream().map(ToolCallBuilder::build).toList();
        Message message = new Message(Role.ASSISTANT, text.isEmpty() ? null : text.toString(), assembled, null);
        ModelResponse modelResponse = new ModelResponse(message, usage, finishReason);

        publish(new ModelResponseEvent(executorName, modelResponse));
        assembled.forEach(listener::onToolCall);
        listener.onComplete(modelResponse);
    }

    private void checkConfigured(String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is not configured. Set 'openai.api.key' "
                    + "(for example via the OPENAI_API_KEY environment variable).");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("No OpenAI model specified. Pass a model name per call "
                    + "(chat/chatStream overloads that take a model name) or configure a default via "
                    + "'openai.model.name'.");
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
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OpenAI request", e);
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

    /** Accumulates the pieces of a single tool call as they arrive across streamed deltas. */
    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCall build() {
            return new ToolCall(id, name, arguments.toString());
        }
    }
}