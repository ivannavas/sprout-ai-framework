package io.github.ivannavas.sprout.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serves an {@link McpServer} over HTTP with {@link McpServer#serveHttp(int)} and drives it end-to-end
 * with a real {@link McpClient} pointed at its URL — exercising both HTTP transports (server and
 * client) against each other. Also checks that a notification POST is answered with {@code 202}.
 */
class McpServerHttpTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @Mcp(name = "calc", version = "1.0")
    static class Calculator {
        @Tool(name = "add", description = "Add two integers")
        public int add(int a, int b) {
            return a + b;
        }
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer() {
        SproutContainer container = new SproutContainer(McpServerHttpTest.class, Logger.getLogger("test"));
        container.registerSingleton(Calculator.class, new Calculator());
        try {
            server = McpServer.from(container).serveHttp(0); // ephemeral port
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    void clientReachesServerOverHttp() throws Exception {
        String url = startServer();
        try (McpClient client = new McpClient("http", url)) {
            assertEquals(1, client.tools().size());
            assertEquals("add", client.tools().get(0).name());

            ToolResult result = client.call(new ToolCall("c1", "add", "{\"a\":2,\"b\":40}"));
            assertFalse(result.error());
            assertEquals("42", result.content());
        }
    }

    @Test
    void notificationPostIsAccepted() throws Exception {
        String url = startServer();
        String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(notification, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertEquals(202, response.statusCode());
    }

    @Test
    void requestGetsJsonResponse() throws Exception {
        String url = startServer();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\",\"params\":{}}";

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonNode body = json.readTree(response.body());
        assertEquals(7, body.path("id").asInt());
        assertEquals("calc", body.path("result").path("serverInfo").path("name").asText());
    }
}
