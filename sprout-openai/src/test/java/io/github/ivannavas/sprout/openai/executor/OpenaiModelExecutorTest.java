package io.github.ivannavas.sprout.openai.executor;

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
}
