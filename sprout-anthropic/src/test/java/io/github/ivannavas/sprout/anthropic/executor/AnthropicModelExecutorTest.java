package io.github.ivannavas.sprout.anthropic.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    void usesPerCallModelWhenNoDefaultConfigured() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AnthropicModelExecutor executor = executorReturning(
                "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\"}", body);
        executor.modelName = null; // no configured default; the model comes from the call

        executor.chat("claude-per-call", new ModelRequest(List.of(Message.user("Hi?")), List.of()));

        assertEquals("claude-per-call", JSON.readTree(body.get()).path("model").asText());
    }
}
