package io.github.ivannavas.sprout.monitoring.itest;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;

import java.util.List;

/** Offline model: asks for the agent's {@code ping} tool on the first turn, then answers — two turns. */
@Model
public class StubModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        boolean toolAnswered = request.messages().stream().anyMatch(m -> m.toolResult() != null);
        if (!toolAnswered) {
            ToolCall call = new ToolCall("c1", "ping", "{\"value\":\"hi\"}");
            return new ModelResponse(Message.assistant(null, List.of(call)),
                    new TokenUsage(10, 5), FinishReason.TOOL_CALLS);
        }
        return new ModelResponse(Message.assistant("pong"), new TokenUsage(20, 7), FinishReason.STOP);
    }
}
