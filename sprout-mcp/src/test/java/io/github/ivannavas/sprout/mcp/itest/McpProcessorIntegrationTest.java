package io.github.ivannavas.sprout.mcp.itest;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.mcp.McpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots a real container over the fixtures in this package and verifies that {@code McpProcessor}
 * auto-exposes both a standalone {@code @Mcp} bean and one that is also an {@code @Agent}, while the
 * agent itself is still built. (The JSON-RPC call path itself is covered by {@code McpServerTest}.)
 */
class McpProcessorIntegrationTest {

    @Test
    void autoExposesStandaloneAndCombinedToolsOnASharedServer() {
        SproutContainer container = SproutApplication.run(McpProcessorIntegrationTest.class);

        McpServer server = container.getSingleton(McpServer.class);
        assertNotNull(server, "McpProcessor should auto-register an McpServer singleton");

        // Standalone @Mcp tool and the @Agent @Mcp tool both land on the one server.
        assertTrue(server.tools().containsKey("hello"));
        assertTrue(server.tools().containsKey("ping"));

        // The combined class is still wired as an agent.
        assertNotNull(container.getSingleton("combinedAgentExecutor"),
                "@Agent @Mcp class should still build its agent executor");
    }
}
