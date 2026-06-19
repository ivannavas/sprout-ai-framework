package io.github.ivannavas.sprout.mcp;

import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;
import io.github.ivannavas.sprout.model.AgentData;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves an agent can call a tool that lives on a (in-process) MCP server: the model asks for the
 * remote tool, the executor routes the call through the {@link McpClient}, and the result flows back.
 */
class AgentUsesMcpTest {

    @Mcp(name = "math", version = "1.0")
    static class MathTools {
        @Tool(name = "add", description = "Add two integers")
        public int add(int a, int b) {
            return a + b;
        }
    }

    /** Asks for the remote "add" tool first, then echoes its result as the final answer. */
    static class ToolThenAnswerModel extends ModelExecutor {
        @Override
        public ModelResponse chat(ModelRequest request) {
            boolean toolDone = request.messages().stream().anyMatch(m -> m.toolResult() != null);
            if (!toolDone) {
                ToolCall call = new ToolCall("call-1", "add", "{\"a\":20,\"b\":22}");
                return new ModelResponse(Message.assistant(null, List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
            }
            String sum = request.messages().stream()
                    .filter(m -> m.toolResult() != null)
                    .reduce((first, second) -> second).orElseThrow()
                    .toolResult().content();
            return new ModelResponse(Message.assistant("The sum is " + sum), TokenUsage.ZERO, FinishReason.STOP);
        }
    }

    private McpClient inProcessClientTo(Object bean) throws Exception {
        SproutContainer container = new SproutContainer(AgentUsesMcpTest.class, Logger.getLogger("test"));
        container.registerSingleton(bean.getClass(), bean);
        McpServer server = McpServer.from(container);

        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientToServer);
        PipedOutputStream serverToClient = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(serverToClient);

        Thread serverThread = new Thread(() -> {
            try {
                server.serveStdio(serverIn, serverToClient);
            } catch (Exception ignored) {
            }
        }, "test-mcp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        return new McpClient("math", clientIn, clientToServer);
    }

    @Test
    void agentCallsAToolHostedOnAnMcpServer() throws Exception {
        AgentData agentData = new AgentData(
                new ToolThenAnswerModel(),
                new InMemoryConversationStore(),
                "",
                4,
                Map.of());
        AgentExecutor agent = new AgentExecutor();
        agent.configure(agentData);
        agent.addToolProvider(inProcessClientTo(new MathTools()));

        AgentResult result = agent.execute("session", "add 20 and 22");

        assertEquals("The sum is 42", result.response());
        assertTrue(result.iterations() >= 2, "expected a tool round-trip before the final answer");
    }
}
