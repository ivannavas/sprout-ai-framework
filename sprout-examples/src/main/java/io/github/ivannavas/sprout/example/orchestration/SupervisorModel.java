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

import java.util.List;

/**
 * Offline model for the delegation supervisor. On the first turn it delegates the question to the right
 * specialist tool (anything number-flavoured to {@code math}, otherwise {@code history}); once the
 * specialist has answered (a {@code TOOL} message) it surfaces that answer as its own reply.
 */
@Model
public class SupervisorModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        List<Message> messages = request.messages();
        Message last = messages.get(messages.size() - 1);

        // The specialist has answered: compose the final reply from its result.
        if (last.role() == Role.TOOL) {
            return new ModelResponse(Message.assistant(last.toolResult().content()),
                    TokenUsage.ZERO, FinishReason.STOP);
        }

        String task = Routing.userText(messages);
        String specialist = Routing.looksMathematical(task) ? "math" : "history";
        ToolCall call = new ToolCall("call-1", specialist, "{\"task\":\"" + task + "\"}");
        return new ModelResponse(Message.assistant("", List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
    }
}
