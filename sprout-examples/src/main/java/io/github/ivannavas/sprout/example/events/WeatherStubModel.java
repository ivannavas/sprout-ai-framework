package io.github.ivannavas.sprout.example.events;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;

import java.util.List;

/**
 * A canned model so the example runs offline: it first asks to call the agent's {@code forecast} tool,
 * then turns the result into a final answer. The two turns (and the tool call between them) are what the
 * agent loop reports as events.
 */
@Model
public class WeatherStubModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        boolean toolAnswered = request.messages().stream().anyMatch(m -> m.toolResult() != null);
        if (!toolAnswered) {
            ToolCall call = new ToolCall("call-1", "forecast", "{\"city\":\"Madrid\"}");
            return new ModelResponse(Message.assistant(null, List.of(call)),
                    new TokenUsage(42, 8), FinishReason.TOOL_CALLS);
        }
        String forecast = request.messages().stream()
                .filter(m -> m.toolResult() != null)
                .reduce((first, second) -> second).orElseThrow()
                .toolResult().content();
        return new ModelResponse(Message.assistant("Here is your forecast: " + forecast),
                new TokenUsage(57, 12), FinishReason.STOP);
    }
}
