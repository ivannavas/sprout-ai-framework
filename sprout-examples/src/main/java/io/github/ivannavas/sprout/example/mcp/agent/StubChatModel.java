package io.github.ivannavas.sprout.example.mcp.agent;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.TokenUsage;

import java.util.List;

/**
 * A canned model standing in for a real LLM: it first asks to call the remote {@code multiply} tool
 * (served by the MCP server), then turns the tool result into a final answer. This is enough to show
 * the agent driving an MCP-hosted tool without needing API credentials.
 */
@Model
public class StubChatModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        boolean toolAnswered = request.messages().stream().anyMatch(m -> m.toolResult() != null);
        if (!toolAnswered) {
            ToolCall call = new ToolCall("call-1", "multiply", "{\"a\":6,\"b\":7}");
            return new ModelResponse(Message.assistant(null, List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        }
        String product = request.messages().stream()
                .filter(m -> m.toolResult() != null)
                .reduce((first, second) -> second).orElseThrow()
                .toolResult().content();
        return new ModelResponse(Message.assistant("6 x 7 = " + product), TokenUsage.ZERO, FinishReason.STOP);
    }
}
