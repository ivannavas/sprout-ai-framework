package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline model for the math specialist. It scans the whole conversation for the most recent message
 * carrying two numbers and adds or multiplies them — scanning the transcript (rather than only the last
 * message) lets the same specialist work whether it is handed a task directly (delegation) or picks up
 * an ongoing conversation (hand-off). Deterministic, so the example needs no API key.
 */
@Model
public class MathModel extends ModelExecutor {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    @Override
    public ModelResponse chat(ModelRequest request) {
        List<Message> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).content();
            if (content == null) {
                continue;
            }
            List<Long> numbers = numbersIn(content);
            if (numbers.size() >= 2) {
                return answer(solve(content.toLowerCase(), numbers.get(0), numbers.get(1)));
            }
        }
        return answer("I need two numbers to compute an answer.");
    }

    private List<Long> numbersIn(String content) {
        List<Long> numbers = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(content);
        while (matcher.find()) {
            numbers.add(Long.parseLong(matcher.group()));
        }
        return numbers;
    }

    private String solve(String task, long a, long b) {
        if (task.contains("plus") || task.contains("sum") || task.contains("add")) {
            return a + " plus " + b + " is " + (a + b) + ".";
        }
        return a + " times " + b + " is " + (a * b) + ".";
    }

    private ModelResponse answer(String text) {
        return new ModelResponse(Message.assistant(text), TokenUsage.ZERO, FinishReason.STOP);
    }
}
