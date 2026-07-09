package io.github.ivannavas.sprout.anthropic.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.StreamListener;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the executor against a loopback HTTP server, so request building and response parsing are
 *  exercised end to end without touching the network. */
class AnthropicModelExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AnthropicModelExecutor executorReturning(String responseJson, AtomicReference<String> capturedBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(requestBytes, StandardCharsets.UTF_8));
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AnthropicModelExecutor executor = new AnthropicModelExecutor();
        executor.apiKey = "test-key";
        executor.modelName = "claude-test";
        executor.maxTokens = 1024;
        executor.requestTimeoutSeconds = 5;
        executor.apiUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/messages";
        return executor;
    }

    @Test
    void parsesTextResponseAndUsage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AnthropicModelExecutor executor = executorReturning(
                "{\"content\":[{\"type\":\"text\",\"text\":\"Sunny in Madrid\"}],"
                        + "\"usage\":{\"input_tokens\":12,\"output_tokens\":7},\"stop_reason\":\"end_turn\"}",
                body);

        ModelRequest request = new ModelRequest(
                List.of(Message.system("You are helpful."), Message.user("Weather in Madrid?")),
                List.of(new ToolDefinition("lookup", "Look up the weather", "{\"type\":\"object\",\"properties\":{}}")));
        ModelResponse response = executor.chat(request);

        assertEquals("Sunny in Madrid", response.message().content());
        assertEquals(12, response.usage().inputTokens());
        assertEquals(7, response.usage().outputTokens());
        assertEquals(FinishReason.STOP, response.finishReason());

        // The system message is lifted out and the tool is advertised.
        JsonNode sent = JSON.readTree(body.get());
        assertEquals("claude-test", sent.path("model").asText());
        assertEquals("You are helpful.", sent.path("system").asText());
        assertEquals("lookup", sent.path("tools").get(0).path("name").asText());
    }

    @Test
    void parsesToolUseResponse() throws Exception {
        AnthropicModelExecutor executor = executorReturning(
                "{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"lookup\",\"input\":{\"city\":\"Madrid\"}}],"
                        + "\"stop_reason\":\"tool_use\"}",
                new AtomicReference<>());

        ModelResponse response = executor.chat(new ModelRequest(List.of(Message.user("Weather?")), List.of()));

        assertTrue(response.message().requestsTools());
        assertEquals("lookup", response.message().toolCalls().get(0).name());
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason());
        assertTrue(response.message().toolCalls().get(0).argumentsJson().contains("Madrid"));
    }

    @Test
    void surfacesHttpErrorBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] response = "{\"error\":\"overloaded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(529, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AnthropicModelExecutor executor = new AnthropicModelExecutor();
        executor.apiKey = "test-key";
        executor.modelName = "claude-test";
        executor.requestTimeoutSeconds = 5;
        executor.apiUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/messages";

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("529"));
        assertTrue(error.getMessage().contains("overloaded"));
    }

    @Test
    void failsFastWhenApiKeyMissing() {
        AnthropicModelExecutor executor = new AnthropicModelExecutor();
        executor.modelName = "claude-test";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("anthropic.api.key"));
    }

    @Test
    void failsWhenNoModelConfiguredOrSupplied() {
        AnthropicModelExecutor executor = new AnthropicModelExecutor();
        executor.apiKey = "test-key";
        // modelName left unset, mirroring a missing 'anthropic.model.name'.

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("anthropic.model.name"));
    }

    @Test
    void streamsTextDeltasTokenByToken() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Sunny "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"in Madrid"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        AnthropicModelExecutor executor = executorReturning(sse, body);

        List<String> tokens = new ArrayList<>();
        AtomicReference<ModelResponse> completed = new AtomicReference<>();
        executor.chatStream(new ModelRequest(List.of(Message.user("Weather?")), List.of()), new StreamListener() {
            @Override
            public void onToken(String token) {
                tokens.add(token);
            }

            @Override
            public void onComplete(ModelResponse response) {
                completed.set(response);
            }
        });

        assertEquals(List.of("Sunny ", "in Madrid"), tokens);
        assertTrue(JSON.readTree(body.get()).path("stream").asBoolean());
        ModelResponse response = completed.get();
        assertEquals("Sunny in Madrid", response.message().content());
        assertEquals(10, response.usage().inputTokens());
        assertEquals(7, response.usage().outputTokens());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    @Test
    void streamsToolUseAssemblingArguments() throws Exception {
        String sse = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"lookup","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"Madrid\\"}"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        AnthropicModelExecutor executor = executorReturning(sse, new AtomicReference<>());

        List<ToolCall> toolCalls = new ArrayList<>();
        AtomicReference<ModelResponse> completed = new AtomicReference<>();
        executor.chatStream(new ModelRequest(List.of(Message.user("Weather?")), List.of()), new StreamListener() {
            @Override
            public void onToolCall(ToolCall toolCall) {
                toolCalls.add(toolCall);
            }

            @Override
            public void onComplete(ModelResponse response) {
                completed.set(response);
            }
        });

        assertEquals(1, toolCalls.size());
        assertEquals("lookup", toolCalls.get(0).name());
        assertEquals("{\"city\":\"Madrid\"}", toolCalls.get(0).argumentsJson());
        assertEquals(FinishReason.TOOL_CALLS, completed.get().finishReason());
        assertTrue(completed.get().message().requestsTools());
    }

    @Test
    void streamReportsErrorToListener() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] response = "{\"error\":\"overloaded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(529, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        AnthropicModelExecutor executor = new AnthropicModelExecutor();
        executor.apiKey = "test-key";
        executor.modelName = "claude-test";
        executor.requestTimeoutSeconds = 5;
        executor.apiUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/messages";

        AtomicReference<Throwable> error = new AtomicReference<>();
        executor.chatStream(new ModelRequest(List.of(Message.user("hi")), List.of()), new StreamListener() {
            @Override
            public void onError(Throwable e) {
                error.set(e);
            }
        });

        assertTrue(error.get().getMessage().contains("529"));
        assertTrue(error.get().getMessage().contains("overloaded"));
    }

    @Test
    void usesPerCallModelWhenNoDefaultConfigured() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AnthropicModelExecutor executor = executorReturning(
                "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\"}", body);
        executor.modelName = null; // no configured default; the model comes from the call

        executor.chat("claude-per-call", new ModelRequest(List.of(Message.user("Hi?")), List.of()));

        assertEquals("claude-per-call", JSON.readTree(body.get()).path("model").asText());
    }
}
