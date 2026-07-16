package io.github.ivannavas.sprout.ollama.executor;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the executor against a loopback HTTP server, so request building and response parsing are
 *  exercised end to end without touching the network. */
class OllamaModelExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OllamaModelExecutor executorReturning(String responseJson, AtomicReference<String> capturedBody) throws IOException {
        return executorReturning("llama-test", responseJson, capturedBody);
    }

    private OllamaModelExecutor executorReturning(String modelName, String responseJson, AtomicReference<String> capturedBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(requestBytes, StandardCharsets.UTF_8));
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        return new OllamaModelExecutor(modelName, 5,
                "http://localhost:" + server.getAddress().getPort() + "/api/chat");
    }

    @Test
    void parsesTextResponseAndUsage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        OllamaModelExecutor executor = executorReturning(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"Sunny in Madrid\"},"
                        + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":12,\"eval_count\":7}",
                body);

        ModelRequest request = new ModelRequest(
                List.of(Message.system("You are helpful."), Message.user("Weather in Madrid?")),
                List.of(new ToolDefinition("lookup", "Look up the weather", "{\"type\":\"object\",\"properties\":{}}")));
        ModelResponse response = executor.chat(request);

        assertEquals("Sunny in Madrid", response.message().content());
        assertEquals(12, response.usage().inputTokens());
        assertEquals(7, response.usage().outputTokens());
        assertEquals(FinishReason.STOP, response.finishReason());

        // Non-streaming call opts out of Ollama's default streaming, and the tool is advertised.
        JsonNode sent = JSON.readTree(body.get());
        assertEquals("llama-test", sent.path("model").asText());
        assertFalse(sent.path("stream").asBoolean());
        assertEquals("system", sent.path("messages").get(0).path("role").asText());
        assertEquals("lookup", sent.path("tools").get(0).path("function").path("name").asText());
    }

    @Test
    void parsesToolCallResponse() throws Exception {
        OllamaModelExecutor executor = executorReturning(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":"
                        + "[{\"function\":{\"name\":\"lookup\",\"arguments\":{\"city\":\"Madrid\"}}}]},"
                        + "\"done\":true,\"done_reason\":\"stop\"}",
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
        server.createContext("/api/chat", exchange -> {
            byte[] response = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OllamaModelExecutor executor = new OllamaModelExecutor("llama-test", 5,
                "http://localhost:" + server.getAddress().getPort() + "/api/chat");

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("404"));
        assertTrue(error.getMessage().contains("model not found"));
    }

    @Test
    void failsWhenNoModelConfiguredOrSupplied() {
        // modelName left null, mirroring a missing 'ollama.model.name'.
        OllamaModelExecutor executor = new OllamaModelExecutor(null, 5, "http://localhost/api/chat");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> executor.chat(new ModelRequest(List.of(Message.user("hi")), List.of())));
        assertTrue(error.getMessage().contains("ollama.model.name"));
    }

    @Test
    void streamsContentTokenByToken() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String ndjson = """
                {"message":{"role":"assistant","content":"Sunny "},"done":false}
                {"message":{"role":"assistant","content":"in Madrid"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":7}
                """;
        OllamaModelExecutor executor = executorReturning(ndjson, body);

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
    void streamsToolCall() throws Exception {
        String ndjson = """
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"lookup","arguments":{"city":"Madrid"}}}]},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","eval_count":9}
                """;
        OllamaModelExecutor executor = executorReturning(ndjson, new AtomicReference<>());

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
        server.createContext("/api/chat", exchange -> {
            byte[] response = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OllamaModelExecutor executor = new OllamaModelExecutor("llama-test", 5,
                "http://localhost:" + server.getAddress().getPort() + "/api/chat");

        AtomicReference<Throwable> error = new AtomicReference<>();
        executor.chatStream(new ModelRequest(List.of(Message.user("hi")), List.of()), new StreamListener() {
            @Override
            public void onError(Throwable e) {
                error.set(e);
            }
        });

        assertTrue(error.get().getMessage().contains("404"));
        assertTrue(error.get().getMessage().contains("model not found"));
    }

    @Test
    void usesPerCallModelWhenNoDefaultConfigured() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        // no configured default; the model comes from the call
        OllamaModelExecutor executor = executorReturning(
                null, "{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"done\":true,\"done_reason\":\"stop\"}", body);

        executor.chat("llama-per-call", new ModelRequest(List.of(Message.user("Hi?")), List.of()));

        assertEquals("llama-per-call", JSON.readTree(body.get()).path("model").asText());
    }
}
