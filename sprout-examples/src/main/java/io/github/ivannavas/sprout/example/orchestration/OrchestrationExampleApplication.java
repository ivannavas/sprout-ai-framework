package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.orchestration.delegation.AgentDelegation;
import io.github.ivannavas.sprout.orchestration.handoff.AgentHandoff;
import io.github.ivannavas.sprout.orchestration.orchestrator.AgentOrchestrator;

import java.util.List;
import java.util.Objects;

/**
 * A research desk that shows {@code sprout-orchestration}'s three patterns <em>composing</em>, around
 * one hub: a <b>supervisor that delegates</b> each question to a {@code math} or {@code history}
 * specialist.
 *
 * <ol>
 *   <li><b>Delegation</b> — the supervisor routes one question to a specialist and composes the reply.</li>
 *   <li><b>Orchestration × delegation</b> — an {@link AgentOrchestrator} runs a whole batch of questions
 *       through that delegating supervisor <em>concurrently</em>.</li>
 *   <li><b>Hand-off × delegation</b> — a triage front desk uses {@link AgentHandoff} to transfer a live
 *       conversation to the same supervisor, which then delegates and answers.</li>
 * </ol>
 *
 * Everything is offline and deterministic, so no API key is needed.
 */
public final class OrchestrationExampleApplication {

    public static void main(String[] args) {
        SproutContainer container = SproutApplication.run(OrchestrationExampleApplication.class);

        AgentExecutor math = container.getSingleton("mathSpecialistExecutor");
        AgentExecutor history = container.getSingleton("historySpecialistExecutor");
        AgentExecutor supervisor = container.getSingleton("supervisorAgentExecutor");
        AgentExecutor triage = container.getSingleton("triageAgentExecutor");

        // The hub: give the supervisor its team of specialists to delegate to. Delegation then happens
        // inside the supervisor's own run, so it composes naturally under both orchestration and hand-off.
        AgentDelegation.builder()
                .specialist("math", "Solves arithmetic and number problems.", math)
                .specialist("history", "Answers history and general-knowledge questions.", history)
                .attachTo(supervisor);

        delegation(supervisor);
        orchestrationWithDelegation(supervisor);
        handoffWithDelegation(triage, supervisor);
    }

    /** 1. Delegation: one question routed to a specialist and composed back. */
    private static void delegation(AgentExecutor supervisor) {
        System.out.println("== Delegation: supervisor -> specialist ==");
        String question = "What is 6 times 7?";
        System.out.println("  " + question + " -> " + supervisor.execute("delegation-demo", question).response());
    }

    /** 2. Orchestration × delegation: a concurrent batch, each item handled by the delegating supervisor. */
    private static void orchestrationWithDelegation(AgentExecutor supervisor) {
        System.out.println("== Orchestration x Delegation: a concurrent batch, each delegated ==");
        List<String> batch = List.of(
                "What is 8 times 9?",
                "Who painted the Mona Lisa?",
                "What is 100 plus 23?");

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(supervisor)) {
            for (String question : batch) {
                orchestrator.execute(question, question, "batch-" + question);
            }
            orchestrator.waitForExecutions();

            for (String question : batch) {
                System.out.println("  " + question + " -> " + answerOf(orchestrator.getResult(question).block()));
            }
        }
    }

    /** 3. Hand-off × delegation: triage transfers control to the supervisor, which then delegates. */
    private static void handoffWithDelegation(AgentExecutor triage, AgentExecutor supervisor) {
        System.out.println("== Hand-off x Delegation: triage -> supervisor -> specialist ==");
        AgentHandoff desk = AgentHandoff.builder()
                .member("triage", "Front desk; escalates the conversation to the supervisor.", triage)
                .member("supervisor", "Researches by delegating to the math and history specialists.", supervisor)
                .build();

        for (String request : List.of("Who painted the Mona Lisa?", "What is 12 plus 30?")) {
            AgentHandoff.HandoffResult result = desk.run(request);
            System.out.println("  " + request);
            System.out.println("    path:   " + String.join(" -> ", result.path()) + " (supervisor delegates internally)");
            System.out.println("    answer: " + result.response());
        }
    }

    private static String answerOf(AgentResult result) {
        return Objects.requireNonNull(result).response();
    }
}
