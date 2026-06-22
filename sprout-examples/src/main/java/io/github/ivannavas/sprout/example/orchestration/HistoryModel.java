package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;

import java.util.List;
import java.util.Map;

/**
 * Offline model for the history specialist. It scans the whole conversation for the most recent message
 * matching a known fact — scanning the transcript (rather than only the last message) lets the same
 * specialist work whether it is handed a task directly (delegation) or picks up an ongoing conversation
 * (hand-off). Deterministic, so the example needs no API key.
 */
@Model
public class HistoryModel extends ModelExecutor {

    private static final Map<String, String> FACTS = Map.of(
            "mona lisa", "Leonardo da Vinci painted the Mona Lisa, around 1503-1506.",
            "moon", "Apollo 11 first landed humans on the Moon in 1969.",
            "rome", "Rome was, according to legend, founded in 753 BC."
    );

    @Override
    public ModelResponse chat(ModelRequest request) {
        List<Message> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).content();
            if (content == null) {
                continue;
            }
            String lower = content.toLowerCase();
            for (Map.Entry<String, String> fact : FACTS.entrySet()) {
                if (lower.contains(fact.getKey())) {
                    return answer(fact.getValue());
                }
            }
        }
        return answer("I don't have that piece of history at hand.");
    }

    private ModelResponse answer(String text) {
        return new ModelResponse(Message.assistant(text), TokenUsage.ZERO, FinishReason.STOP);
    }
}
