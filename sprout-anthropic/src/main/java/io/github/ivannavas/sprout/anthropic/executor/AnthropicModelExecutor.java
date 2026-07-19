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
 *
 * <h2>Prompt caching</h2>
 *
 * <p>On by default; set {@code anthropic.cache.enabled=false} to turn it off. Two breakpoints are
 * placed per request: one closing the system prompt, which also covers the tool schemas since they
 * render ahead of it, and one on the newest message, so a growing conversation is read back from
 * cache instead of reprocessed. That is what makes the agent loop affordable — without it every
 * iteration pays full price for the whole transcript again.
 *
 * <p>Caching is a prefix match, so anything that varies inside the prefix from one call to the next —
 * a timestamp in the system prompt, a tool list built in a different order — silently prevents a hit.
 * {@link io.github.ivannavas.sprout.model.TokenUsage#cacheReadTokens()} is how you confirm it is
 * working: if it stays at zero across calls that share a prefix, something in that prefix is moving.
 *
 * <p>Leaving it on is the right call for agents and any repeated prefix. It is not free in every
 * case: a cache write costs more than plain input, so one-shot calls that never reuse their prefix
 * pay a premium for a cache nothing reads. Short prompts are unaffected either way — a prefix below
 * the model's minimum cacheable size is silently not cached rather than rejected.
 */
@Model("anthropic")
public class AnthropicModelExecutor extends ModelExecutor {

    private final String apiKey;
    private final String modelName;
    private final int maxTokens;
    private final int requestTimeoutSeconds;
    private final String apiUrl;
    private final boolean cacheEnabled;

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Map<String, Object> EPHEMERAL = Map.of("type", "ephemeral");

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
            @Value("${anthropic.api.url:https://api.anthropic.com/v1/messages}") String apiUrl,
            @Value("${anthropic.cache.enabled:true}") boolean cacheEnabled) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.apiUrl = apiUrl;
        this.cacheEnabled = cacheEnabled;
    }

    /** Constructs the executor with prompt caching on, as {@code anthropic.cache.enabled} defaults to. */
    public AnthropicModelExecutor(String apiKey, String modelName, int maxTokens,
                                  int requestTimeoutSeconds, String apiUrl) {
        this(apiKey, modelName, maxTokens, requestTimeoutSeconds, apiUrl, true);
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
                if (data.isEmpty()) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(data);
                String type = node.path("type").asText("");
                switch (type) {
                    case "message_start" -> usage = toTokenUsage(node.path("message").path("usage"));
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
                        // Only the output count is refreshed here; the input and cache counts are
                        // final as of message_start and must survive this update.
                        if (node.path("usage").has("output_tokens")) {
                            usage = new TokenUsage(
                                    usage.inputTokens(),
                                    node.path("usage").get("output_tokens").asLong(),
                                    usage.cacheWriteTokens(),
                                    usage.cacheReadTokens());
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
        ModelResponse modelResponse = new ModelResponse(message, usage, finishReason);

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

        String system = systemMessages.stream()
                .map(Message::content)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);

        List<Map<String, Object>> tools = request.tools().stream().map(this::toToolMap).toList();
        List<Map<String, Object>> messages = conversationMessages.stream()
                .map(this::toMessageMap)
                .collect(Collectors.toCollection(ArrayList::new));

        if (cacheEnabled) {
            // The request renders as tools -> system -> messages, so a single breakpoint at the end of
            // the system prompt caches the tool schemas along with it. Only when there is no system
            // prompt do the tools need one of their own.
            if (!system.isEmpty()) {
                body.put("system", List.of(withCacheControl(textBlock(system))));
            } else if (!tools.isEmpty()) {
                tools = new ArrayList<>(tools);
                tools.set(tools.size() - 1, withCacheControl(tools.getLast()));
            }
            // A second breakpoint on the newest message caches the conversation as it grows: each call
            // reads back everything cached by the previous one and writes only what it appended.
            if (!messages.isEmpty()) {
                markCacheBreakpoint(messages.getLast());
            }
        } else if (!system.isEmpty()) {
            body.put("system", system);
        }

        body.put("messages", messages);

        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }
        return body;
    }

    private static Map<String, Object> textBlock(String text) {
        return Map.of("type", "text", "text", text);
    }

    /** Returns a copy of {@code block} carrying an ephemeral {@code cache_control} marker. */
    private static Map<String, Object> withCacheControl(Map<String, Object> block) {
        Map<String, Object> marked = new LinkedHashMap<>(block);
        marked.put("cache_control", EPHEMERAL);
        return marked;
    }

    /**
     * Puts a cache breakpoint on the last content block of {@code message}, normalising plain-string
     * content into a text block first, since {@code cache_control} can only sit on a block. Content is
     * rebuilt rather than mutated in place: {@link #toMessageMap} assembles blocks with immutable maps.
     */
    private static void markCacheBreakpoint(Map<String, Object> message) {
        Object content = message.get("content");
        if (content instanceof String text) {
            message.put("content", List.of(withCacheControl(textBlock(text))));
        } else if (content instanceof List<?> blocks && !blocks.isEmpty()
                && blocks.getLast() instanceof Map<?, ?> last) {
            List<Object> marked = new ArrayList<>(blocks);
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) last;
            marked.set(marked.size() - 1, withCacheControl(block));
            message.put("content", marked);
        }
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

    /**
     * Reads a {@code usage} object. The cache counts are absent from the response unless the request
     * carried {@code cache_control}, so they default to zero. Anthropic reports them alongside
     * {@code input_tokens} rather than inside it: the three are billed at different rates and only
     * their sum is the size of the prompt.
     */
    private static TokenUsage toTokenUsage(JsonNode usage) {
        return new TokenUsage(
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0),
                usage.path("cache_read_input_tokens").asLong(0));
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
            usage = toTokenUsage(root.get("usage"));
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
