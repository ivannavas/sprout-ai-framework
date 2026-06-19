package io.github.ivannavas.sprout.example.spring;

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
 * A deterministic, offline stand-in for a real LLM so the example runs without API keys or network.
 *
 * <p>On the first turn it asks the agent to call its first tool (passing the user's text as the
 * {@code city} argument, which matches {@link WeatherAgent#lookup(String)}); on the next turn it
 * turns the tool's output into a final answer.
 */
@Model("stub")
public class StubChatModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        List<Message> messages = request.messages();
        Message last = messages.getLast();

        // The tool has already answered: produce the final reply.
        if (last.role() == Role.TOOL) {
            String toolOutput = last.toolResult().content();
            return new ModelResponse(
                    Message.assistant("Forecast: " + toolOutput),
                    new TokenUsage(12, 6),
                    FinishReason.STOP);
        }

        // First turn: if the agent exposes tools, invoke the first one.
        if (!request.tools().isEmpty()) {
            ToolDefinition tool = request.tools().get(0);
            String userText = lastUserText(messages);
            ToolCall call = new ToolCall("call-1", tool.name(), "{\"city\":\"" + userText + "\"}");
            return new ModelResponse(
                    Message.assistant("", List.of(call)),
                    new TokenUsage(8, 3),
                    FinishReason.TOOL_CALLS);
        }

        return new ModelResponse(Message.assistant("Hello!"), new TokenUsage(4, 2), FinishReason.STOP);
    }

    private String lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.get(i).content();
            }
        }
        return "";
    }
}
