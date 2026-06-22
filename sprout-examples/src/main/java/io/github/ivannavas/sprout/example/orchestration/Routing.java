package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.Role;

import java.util.List;

/** Tiny shared routing helpers used by the supervisor model to pick a specialist. */
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

    /**
     * The first user message in the conversation. The supervisor classifies this — using the first
     * (the original request) rather than the last keeps it working both as an entry agent and when it
     * picks up a hand-off, where the latest user message is just a "continue" nudge.
     */
    static String firstUserText(List<Message> messages) {
        for (Message message : messages) {
            if (message.role() == Role.USER) {
                return message.content();
            }
        }
        return "";
    }
}
