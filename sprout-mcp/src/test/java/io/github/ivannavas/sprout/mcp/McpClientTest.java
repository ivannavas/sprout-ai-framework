package io.github.ivannavas.sprout.mcp;

import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import io.github.ivannavas.sprout.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link McpClient} against a real {@link McpServer} wired over in-memory pipes — the same
 * JSON-RPC the stdio transport carries, without spawning a process.
 */
class McpClientTest {

    @Mcp(name = "greeter", version = "1.0")
    static class Greeter {
        @Tool(name = "greet", description = "Greet someone")
        public String greet(String who) {
            return "hello " + who;
        }
    }

    private McpClient connectedClientTo(Object bean) throws Exception {
        SproutContainer container = new SproutContainer(McpClientTest.class, Logger.getLogger("test"));
        container.registerSingleton(bean.getClass(), bean);
        McpServer server = McpServer.from(container);

        // client.out -> server.in, server.out -> client.in
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientToServer);
        PipedOutputStream serverToClient = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverToClient);

        Thread serverThread = new Thread(() -> {
            try {
                server.serveStdio(serverIn, serverToClient);
            } catch (Exception ignored) {
                // The pipe closes when the test ends; nothing to do.
            }
        }, "test-mcp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        return new McpClient("test", clientIn, clientToServer);
    }

    @Test
    void listsRemoteToolsWithSchema() throws Exception {
        McpClient client = connectedClientTo(new Greeter());

        assertEquals(1, client.tools().size());
        ToolDefinition greet = client.tools().get(0);
        assertEquals("greet", greet.name());
        assertEquals("Greet someone", greet.description());
        assertTrue(greet.parametersJson().contains("\"who\""));

        client.close();
    }

    @Test
    void callsRemoteTool() throws Exception {
        McpClient client = connectedClientTo(new Greeter());

        ToolResult result = client.call(new ToolCall("c1", "greet", "{\"who\":\"sprout\"}"));

        assertFalse(result.error());
        assertEquals("hello sprout", result.content());

        client.close();
    }
}
