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
 * Offline model for the hand-off triage agent. On the first turn it hands the conversation off to a
 * specialist (via a {@code handoff_to_*} tool the team wired in) instead of answering itself; once the
 * transfer is acknowledged it steps aside so the specialist takes over and produces the final answer.
 */
@Model
public class TriageModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        List<Message> messages = request.messages();
        Message last = messages.get(messages.size() - 1);

        // The transfer has been acknowledged: step aside for the specialist.
        if (last.role() == Role.TOOL) {
            return new ModelResponse(Message.assistant("Putting you through now."),
                    TokenUsage.ZERO, FinishReason.STOP);
        }

        String target = Routing.looksMathematical(Routing.userText(messages)) ? "math" : "history";
        String toolName = "handoff_to_" + target;
        if (hasTool(request.tools(), toolName)) {
            ToolCall call = new ToolCall("call-1", toolName, "{}");
            return new ModelResponse(Message.assistant("", List.of(call)),
                    TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        }

        return new ModelResponse(Message.assistant("I'm not sure who handles that — could you rephrase?"),
                TokenUsage.ZERO, FinishReason.STOP);
    }

    private boolean hasTool(List<ToolDefinition> tools, String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name));
    }
}
