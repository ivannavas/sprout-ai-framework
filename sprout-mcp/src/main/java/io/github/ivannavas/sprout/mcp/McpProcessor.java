package io.github.ivannavas.sprout.mcp;

import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;
import io.github.ivannavas.sprout.processor.ComponentProcessor;

import java.io.IOException;
import java.util.logging.Level;

/**
 * Processor for {@link Mcp @Mcp} components, contributed by the {@code sprout-mcp} module. Registers
 * every {@code @Mcp} bean's {@code @Tool} methods on a shared {@link McpServer} singleton. Cooperates
 * with the agent processor: an {@code @Agent @Mcp} class is built as an agent and also exposed over
 * MCP, sharing one bean instance. Once the container is fully wired the server auto-starts over stdio
 * (unless {@code sprout.mcp.auto-start=false}) and, when {@code sprout.mcp.http.port} is set, also
 * over HTTP on that port.
 */
@Processor(Mcp.class)
public class McpProcessor extends ComponentProcessor {

    /** Property controlling whether the aggregated server auto-starts over stdio. Defaults to true. */
    public static final String AUTO_START_PROPERTY = "sprout.mcp.auto-start";

    /** Port for auto-starting the aggregated server over HTTP. Unset (or blank) means no HTTP server. */
    public static final String HTTP_PORT_PROPERTY = "sprout.mcp.http.port";

    public McpProcessor(Class<?> component, SproutContainer sproutContainer) {
        super(component, sproutContainer);
    }

    @Override
    public Object instantiate() {
        Object bean = super.instantiate();
        mcpServer().register(bean);
        return bean;
    }

    private McpServer mcpServer() {
        McpServer server = sproutContainer.getSingleton(McpServer.class);
        if (server == null) {
            server = new McpServer();
            sproutContainer.registerSingleton(McpServer.class, server);
            scheduleAutoStart(server);
        }
        return server;
    }

    private void scheduleAutoStart(McpServer server) {
        boolean stdio = Boolean.parseBoolean(sproutContainer.getProperty(AUTO_START_PROPERTY, "true"));
        int httpPort = parseHttpPort();
        if (!stdio && httpPort < 0) {
            return;
        }
        // Defer until the container is fully wired so every @Mcp bean's tools are registered before a
        // client can list them. Both transports are independent: stdio (on by default) and HTTP (only
        // when a port is configured) can run together or on their own.
        sproutContainer.onReady(() -> {
            if (stdio) {
                startStdio(server);
            }
            if (httpPort >= 0) {
                startHttp(server, httpPort);
            }
        });
    }

    private void startStdio(McpServer server) {
        // Served on a non-daemon thread so the JVM stays alive until stdin closes, without blocking
        // SproutApplication.run from returning.
        Thread thread = new Thread(() -> {
            try {
                server.serveStdio();
            } catch (IOException e) {
                sproutContainer.logger().log(Level.SEVERE, "Sprout MCP: stdio server stopped", e);
            }
        }, "sprout-mcp");
        thread.start();
        sproutContainer.logger().info("Sprout MCP: serving " + server.tools().size() + " tool(s) over stdio");
    }

    private void startHttp(McpServer server, int port) {
        try {
            server.serveHttp(port);
            sproutContainer.logger().info("Sprout MCP: serving " + server.tools().size()
                    + " tool(s) over HTTP on port " + port);
        } catch (IOException e) {
            sproutContainer.logger().log(Level.SEVERE, "Sprout MCP: failed to start HTTP server on port " + port, e);
        }
    }

    private int parseHttpPort() {
        String value = sproutContainer.getProperty(HTTP_PORT_PROPERTY, "");
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid " + HTTP_PORT_PROPERTY + ": '" + value + "' is not a port number", e);
        }
    }
}
