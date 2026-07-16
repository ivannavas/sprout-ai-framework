package io.github.ivannavas.sprout.anthropic.executor;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.annotation.Autowired;
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
 * {@link ModelExecutor} for Anthropic's Messages API, registered under the bean name
 * {@code anthropic}. Configure {@code anthropic.api.key} (and optionally {@code anthropic.max.tokens},
 * {@code anthropic.timeout.seconds} and {@code anthropic.api.url}); the API key also resolves from the
 * {@code ANTHROPIC_API_KEY} environment variable.
 *
 * <p>The model can be chosen per call via {@link #chat(String, ModelRequest)} /
 * {@code chatStream(String, ...)}. Configuring {@code anthropic.model.name} is optional: it only
 * supplies the default used by the no-arg {@link #chat(ModelRequest)} overload, so it is not
 * required when every call names its own model.
 *
 * <p>{@code chatStream} is a real token-by-token stream: it sets {@code stream: true} and consumes
 * the Server-Sent Events response, forwarding each {@code text_delta} to {@link StreamListener#onToken}
 * as it arrives and assembling the full {@link ModelResponse} for {@link StreamListener#onComplete}.
 */
@Model("anthropic")
public class AnthropicModelExecutor extends ModelExecutor {

    private final String apiKey;
    private final String modelName;
    private final int maxTokens;
    private final int requestTimeoutSeconds;
    private final String apiUrl;

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    protected HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AnthropicModelExecutor(
            @Value("${anthropic.api.key:}") String apiKey,
            @Value("${anthropic.model.name:}") String modelName,
            @Value("${anthropic.max.tokens:4096}") int maxTokens,
            @Value("${anthropic.timeout.seconds:60}") int requestTimeoutSeconds,
            @Value("${anthropic.api.url:https://api.anthropic.com/v1/messages}") String apiUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.apiUrl = apiUrl;
    }

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
                throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + response.body());
            }
            return parseResponse(objectMapper.readTree(response.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Anthropic chat request failed", e);
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
     * Opens a streaming Messages call and drives {@code listener} from the SSE feed: {@code text_delta}
     * chunks are forwarded via {@link StreamListener#onToken}, {@code tool_use} blocks and their
     * {@code input_json_delta} fragments are accumulated by index, and once the stream ends the
     * assembled {@link ModelResponse} is published as a {@link ModelResponseEvent} and delivered
     * through {@link StreamListener#onComplete}.
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
            throw new RuntimeException("Anthropic API error " + response.statusCode() + ": " + error);
        }

        StringBuilder text = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolBlocks = new LinkedHashMap<>();
        long inputTokens = 0;
        long outputTokens = 0;
        FinishReason finishReason = FinishReason.STOP;

        try (Stream<String> lines = response.body()) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line == null || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(data);
                String type = node.path("type").asText("");
                switch (type) {
                    case "message_start" -> {
                        JsonNode usage = node.path("message").path("usage");
                        if (usage.has("input_tokens")) inputTokens = usage.get("input_tokens").asLong();
                        if (usage.has("output_tokens")) outputTokens = usage.get("output_tokens").asLong();
                    }
                    case "content_block_start" -> {
                        JsonNode block = node.path("content_block");
                        if ("tool_use".equals(block.path("type").asText())) {
                            int index = node.path("index").asInt();
                            ToolCallBuilder builder = new ToolCallBuilder();
                            builder.id = block.path("id").asText();
                            builder.name = block.path("name").asText();
                            toolBlocks.put(index, builder);
                        }
                    }
                    case "content_block_delta" -> {
                        JsonNode delta = node.path("delta");
                        String deltaType = delta.path("type").asText("");
                        if ("text_delta".equals(deltaType)) {
                            String token = delta.path("text").asText();
                            if (!token.isEmpty()) {
                                text.append(token);
                                listener.onToken(token);
                            }
                        } else if ("input_json_delta".equals(deltaType)) {
                            ToolCallBuilder builder = toolBlocks.get(node.path("index").asInt());
                            if (builder != null) {
                                builder.arguments.append(delta.path("partial_json").asText());
                            }
                        }
                    }
                    case "message_delta" -> {
                        JsonNode delta = node.path("delta");
                        if (delta.has("stop_reason") && !delta.get("stop_reason").isNull()) {
                            finishReason = mapStopReason(delta.get("stop_reason").asText());
                        }
                        if (node.path("usage").has("output_tokens")) {
                            outputTokens = node.path("usage").get("output_tokens").asLong();
                        }
                    }
                    default -> {
                        // message_stop, ping, content_block_stop: nothing to accumulate
                    }
                }
            }
        }

        List<ToolCall> assembled = toolBlocks.values().stream().map(ToolCallBuilder::build).toList();
        Message message = new Message(Role.ASSISTANT, text.isEmpty() ? null : text.toString(), assembled, null);
        ModelResponse modelResponse = new ModelResponse(
                message, new TokenUsage(inputTokens, outputTokens), finishReason);

        publish(new ModelResponseEvent(executorName, modelResponse));
        assembled.forEach(listener::onToolCall);
        listener.onComplete(modelResponse);
    }

    private void checkConfigured(String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Anthropic API key is not configured. Set 'anthropic.api.key' "
                    + "(for example via the ANTHROPIC_API_KEY environment variable).");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalStateException("No Anthropic model specified. Pass a model name per call "
                    + "(chat/chatStream overloads that take a model name) or configure a default via "
                    + "'anthropic.model.name'.");
        }
    }

    private Map<String, Object> buildRequestBody(String modelName, ModelRequest request) {
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
        return body;
    }

    private HttpRequest buildHttpRequest(Map<String, Object> body) {
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Anthropic request", e);
        }
    }

    private static FinishReason mapStopReason(String stopReason) {
        return switch (stopReason) {
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.MAX_TOKENS;
            default -> FinishReason.STOP;
        };
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

        FinishReason finishReason = mapStopReason(root.get("stop_reason").asText());

        return new ModelResponse(message, usage, finishReason);
    }

    /** Accumulates the pieces of a single {@code tool_use} block as they arrive across streamed deltas. */
    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCall build() {
            // Anthropic omits input_json_delta entirely for a no-argument tool call.
            String args = arguments.length() == 0 ? "{}" : arguments.toString();
            return new ToolCall(id, name, args);
        }
    }
}
