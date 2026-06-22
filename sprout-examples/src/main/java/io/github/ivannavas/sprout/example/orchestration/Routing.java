package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.Role;

import java.util.List;

/** Tiny shared routing helpers used by the supervisor and triage models to pick a specialist. */
final class Routing {

    private Routing() {
    }

    /** Whether a request looks like arithmetic, so it should go to the math specialist. */
    static boolean looksMathematical(String task) {
        String lower = task.toLowerCase();
        return lower.matches(".*\\d.*")
                || lower.contains("times") || lower.contains("plus")
                || lower.contains("multiply") || lower.contains("sum") || lower.contains("add");
    }

    /** The most recent user message's text, which the routers classify. */
    static String userText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.get(i).content();
            }
        }
        return "";
    }
}
