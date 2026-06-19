package io.github.ivannavas.sprout.mcp;

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
 * MCP, sharing one bean instance. Unless {@code sprout.mcp.auto-start=false}, the server serves stdio
 * once the container is fully wired.
 */
@Processor(Mcp.class)
public class McpProcessor extends ComponentProcessor {

    /** Property controlling whether the aggregated server auto-starts over stdio. Defaults to true. */
    public static final String AUTO_START_PROPERTY = "sprout.mcp.auto-start";

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
        if (!Boolean.parseBoolean(sproutContainer.getProperty(AUTO_START_PROPERTY, "true"))) {
            return;
        }
        // Defer until the container is fully wired so every @Mcp bean's tools are registered before
        // a client can list them. Served on a non-daemon thread so the JVM stays alive until stdin
        // closes, without blocking SproutApplication.run from returning.
        sproutContainer.onReady(() -> {
            Thread thread = new Thread(() -> {
                try {
                    server.serveStdio();
                } catch (IOException e) {
                    sproutContainer.logger().log(Level.SEVERE, "Sprout MCP: stdio server stopped", e);
                }
            }, "sprout-mcp");
            thread.start();
            sproutContainer.logger().info("Sprout MCP: serving " + server.tools().size() + " tool(s) over stdio");
        });
    }
}
