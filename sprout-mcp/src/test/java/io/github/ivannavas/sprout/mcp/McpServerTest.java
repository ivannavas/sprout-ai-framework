package io.github.ivannavas.sprout.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Mcp(name = "calc", version = "1.2.3")
    static class Calculator {
        @Tool(name = "add", description = "Add two integers")
        public int add(int a, int b) {
            return a + b;
        }

        @Tool(description = "Always fails")
        public String boom() {
            throw new IllegalStateException("nope");
        }
    }

    private McpServer serverWith(Object bean) {
        SproutContainer container = new SproutContainer(McpServerTest.class, Logger.getLogger("test"));
        container.registerSingleton(bean.getClass(), bean);
        return McpServer.from(container);
    }

    @Test
    void discoversToolsFromMcpBeans() {
        McpServer server = serverWith(new Calculator());
        assertEquals(2, server.tools().size());
        assertTrue(server.tools().containsKey("add"));
        assertTrue(server.tools().containsKey("boom"));
    }

    @Test
    void initializeAdvertisesServerInfoAndToolCapability() {
        McpServer server = serverWith(new Calculator());
        JsonNode response = server.handle(request(1, "initialize", "{}"));

        JsonNode result = response.get("result");
        assertEquals("calc", result.path("serverInfo").path("name").asText());
        assertEquals("1.2.3", result.path("serverInfo").path("version").asText());
        assertTrue(result.path("capabilities").has("tools"));
    }

    @Test
    void toolsListReturnsNameDescriptionAndSchema() {
        McpServer server = serverWith(new Calculator());
        JsonNode tools = server.handle(request(2, "tools/list", "{}")).path("result").path("tools");

        JsonNode add = null;
        for (JsonNode tool : tools) {
            if (tool.path("name").asText().equals("add")) {
                add = tool;
            }
        }
        assertEquals("Add two integers", add.path("description").asText());
        assertEquals("object", add.path("inputSchema").path("type").asText());
        assertEquals("integer", add.path("inputSchema").path("properties").path("a").path("type").asText());
    }

    @Test
    void toolsCallInvokesTheMethod() {
        McpServer server = serverWith(new Calculator());
        JsonNode result = server.handle(
                request(3, "tools/call", "{\"name\":\"add\",\"arguments\":{\"a\":2,\"b\":40}}")).path("result");

        assertFalse(result.path("isError").asBoolean());
        assertEquals("42", result.path("content").get(0).path("text").asText());
    }

    @Test
    void toolsCallReportsToolFailureAsError() {
        McpServer server = serverWith(new Calculator());
        JsonNode result = server.handle(
                request(4, "tools/call", "{\"name\":\"boom\",\"arguments\":{}}")).path("result");

        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("nope"));
    }

    @Test
    void unknownToolIsAJsonRpcError() {
        McpServer server = serverWith(new Calculator());
        JsonNode response = server.handle(
                request(5, "tools/call", "{\"name\":\"missing\",\"arguments\":{}}"));

        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    @Test
    void notificationsGetNoResponse() {
        McpServer server = serverWith(new Calculator());
        JsonNode notification = json.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized");
        assertNull(server.handle(notification));
    }

    @Test
    void serveStdioProcessesNewlineDelimitedRequests() throws Exception {
        McpServer server = serverWith(new Calculator());
        String input = json.writeValueAsString(request(1, "tools/call",
                "{\"name\":\"add\",\"arguments\":{\"a\":1,\"b\":1}}")) + "\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serveStdio(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);

        JsonNode response = json.readTree(out.toString(StandardCharsets.UTF_8).trim());
        assertEquals("2", response.path("result").path("content").get(0).path("text").asText());
    }

    private JsonNode request(int id, String method, String paramsJson) {
        try {
            return json.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method)
                    .set("params", json.readTree(paramsJson));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
