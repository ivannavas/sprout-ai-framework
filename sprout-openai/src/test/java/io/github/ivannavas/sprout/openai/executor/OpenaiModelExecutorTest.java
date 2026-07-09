package io.github.ivannavas.sprout.openai.executor;

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
class OpenaiModelExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenaiModelExecutor executorReturning(String responseJson, AtomicReference<String> capturedBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(requestBytes, StandardCharsets.UTF_8));
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenaiModelExecutor executor = new OpenaiModelExecutor();
        executor.apiKey = "test-key";
        executor.modelName = "gpt-test";
        executor.requestTimeoutSeconds = 5;
        executor.apiUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions";
        return executor;
    }

    @Test
    void parsesTextResponseAndUsage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        OpenaiModelExecutor executor = executorReturning(
                "{\"choices\":[{\"message\":{\"content\":\"Sunny in Madrid\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7}}",
                body);

        ModelRequest request = new ModelRequest(
                List.of(Message.system("You are helpful."), Message.user("Weather in Madrid?")),
                List.of(new ToolDefinition("lookup", "Look up the weather", "{\"type\":\"object\",\"properties\":{}}")));
        ModelResponse response = executor.chat(request);

        assertEquals("Sunny in Madrid", response.message().content());
        assertEquals(12, response.usage().inputTokens());
        assertEquals(7, response.usage().outputTokens());
        assertEquals(FinishReason.STOP, response.finishReason());

        JsonNode sent = JSON.readTree(body.get());
        assertEquals("gpt-test", sent.path("model").asText());
        assertEquals("function", sent.path("tools").get(0).path("type").asText());
        assertEquals("lookup", sent.path("tools").get(0).path("function").path("name").asText());
    }

    @Test
    void parsesToolCallResponse() throws Exception {
        OpenaiModelExecutor executor = executorReturning(
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\\\"Madrid\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}",
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
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenaiModelExecutor executor = new OpenaiModelExecutor();
        executor.apiKey = "test-key";
        executor.modelName = "gpt-test";
        executor.requestTimeoutSeconds = 5;
        executor.apiUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions";

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("429"));
        assertTrue(error.getMessage().contains("rate_limited"));
    }

    @Test
    void failsFastWhenApiKeyMissing() {
        OpenaiModelExecutor executor = new OpenaiModelExecutor();
        executor.modelName = "gpt-test";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("openai.api.key"));
    }

    @Test
    void failsWhenNoModelConfiguredOrSupplied() {
        OpenaiModelExecutor executor = new OpenaiModelExecutor();
        executor.apiKey = "test-key";
        // modelName left unset, mirroring a missing 'openai.model.name'.

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("openai.model.name"));
    }

    @Test
    void streamsContentDeltasTokenByToken() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String sse = """
                data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"content":"Sunny "}}]}

                data: {"choices":[{"delta":{"content":"in Madrid"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7}}

                data: [DONE]
                """;
        OpenaiModelExecutor executor = executorReturning(sse, body);

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
        JsonNode sent = JSON.readTree(body.get());
        assertTrue(sent.path("stream").asBoolean());
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean());
        ModelResponse response = completed.get();
        assertEquals("Sunny in Madrid", response.message().content());
        assertEquals(12, response.usage().inputTokens());
        assertEquals(7, response.usage().outputTokens());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    @Test
    void streamsToolCallAssemblingArgumentsAcrossDeltas() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"t1","type":"function","function":{"name":"lookup","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Madrid\\"}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        OpenaiModelExecutor executor = executorReturning(sse, new AtomicReference<>());

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
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenaiModelExecutor executor = new OpenaiModelExecutor();
        executor.apiKey = "test-key";
        executor.modelName = "gpt-test";
        executor.requestTimeoutSeconds = 5;
        executor.apiUrl = "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions";

        AtomicReference<Throwable> error = new AtomicReference<>();
        executor.chatStream(new ModelRequest(List.of(Message.user("hi")), List.of()), new StreamListener() {
            @Override
            public void onError(Throwable e) {
                error.set(e);
            }
        });

        assertTrue(error.get().getMessage().contains("429"));
        assertTrue(error.get().getMessage().contains("rate_limited"));
    }

    @Test
    void usesPerCallModelWhenNoDefaultConfigured() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        OpenaiModelExecutor executor = executorReturning(
                "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}", body);
        executor.modelName = null; // no configured default; the model comes from the call

        executor.chat("gpt-per-call", new ModelRequest(List.of(Message.user("Hi?")), List.of()));

        assertEquals("gpt-per-call", JSON.readTree(body.get()).path("model").asText());
    }
}
