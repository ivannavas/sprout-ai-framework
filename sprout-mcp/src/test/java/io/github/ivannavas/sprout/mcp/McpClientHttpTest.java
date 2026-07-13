package io.github.ivannavas.sprout.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import io.github.ivannavas.sprout.model.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link McpClient}'s HTTP transport against a minimal JSON-RPC MCP server served over an
 * in-process {@link HttpServer} — connecting to an already-running server by URL, no child process.
 * Covers both the plain {@code application/json} reply and the {@code text/event-stream} (SSE) reply,
 * and asserts the {@code Mcp-Session-Id} handed back on initialize is echoed on later requests.
 */
class McpClientHttpTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a fake MCP server; {@code sse} chooses the reply framing. Returns its base URL. */
    private String startServer(boolean sse) throws IOException {
        AtomicReference<String> session = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, sse, session));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private void handle(HttpExchange exchange, boolean sse, AtomicReference<String> session) throws IOException {
        JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
        String method = request.path("method").asText();

        // Every request after initialize must carry the session id we issued.
        if (!method.equals("initialize")) {
            assertEquals(session.get(), exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                    "client must echo the Mcp-Session-Id from initialize");
        }

        if (method.equals("notifications/initialized")) {
            exchange.sendResponseHeaders(202, -1); // notification: no body
            exchange.close();
            return;
        }

        int id = request.path("id").asInt();
        String result = switch (method) {
            case "initialize" -> {
                session.set("sess-123");
                exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-123");
                yield "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}";
            }
            case "tools/list" -> "{\"tools\":[{\"name\":\"greet\",\"description\":\"Greet someone\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"who\":{\"type\":\"string\"}}}}]}";
            case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"hello "
                    + request.path("params").path("arguments").path("who").asText() + "\"}]}";
            default -> throw new IllegalStateException("unexpected method " + method);
        };

        String rpc = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
        byte[] body = sse
                ? ("event: message\ndata: " + rpc + "\n\n").getBytes(StandardCharsets.UTF_8)
                : rpc.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", sse ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void listsAndCallsToolsOverHttpJson() throws Exception {
        try (McpClient client = new McpClient("http-test", startServer(false))) {
            ToolDefinition greet = client.tools().get(0);
            assertEquals("greet", greet.name());
            assertEquals("Greet someone", greet.description());
            assertTrue(greet.parametersJson().contains("\"who\""));

            ToolResult result = client.call(new ToolCall("c1", "greet", "{\"who\":\"sprout\"}"));
            assertFalse(result.error());
            assertEquals("hello sprout", result.content());
        }
    }

    @Test
    void listsAndCallsToolsOverHttpSse() throws Exception {
        try (McpClient client = new McpClient("http-sse-test", startServer(true))) {
            assertEquals(1, client.tools().size());

            ToolResult result = client.call(new ToolCall("c1", "greet", "{\"who\":\"stream\"}"));
            assertFalse(result.error());
            assertEquals("hello stream", result.content());
        }
    }
}
