package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.Role;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;

import java.util.List;

/**
 * Offline model for the hand-off triage agent — the front desk. It doesn't try to answer; on the first
 * turn it hands the conversation off to the supervisor (via the {@code handoff_to_supervisor} tool the
 * team wired in), then steps aside once the transfer is acknowledged so the supervisor takes over.
 */
@Model
public class TriageModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        List<Message> messages = request.messages();
        Message last = messages.get(messages.size() - 1);

        // The transfer has been acknowledged: step aside for the supervisor.
        if (last.role() == Role.TOOL) {
            return new ModelResponse(Message.assistant("Putting you through to our researcher now."),
                    TokenUsage.ZERO, FinishReason.STOP);
        }

        String toolName = "handoff_to_supervisor";
        if (hasTool(request.tools(), toolName)) {
            ToolCall call = new ToolCall("call-1", toolName, "{}");
            return new ModelResponse(Message.assistant("", List.of(call)),
                    TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        }

        return new ModelResponse(Message.assistant("How can I help?"), TokenUsage.ZERO, FinishReason.STOP);
    }

    private boolean hasTool(List<ToolDefinition> tools, String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name));
    }
}
