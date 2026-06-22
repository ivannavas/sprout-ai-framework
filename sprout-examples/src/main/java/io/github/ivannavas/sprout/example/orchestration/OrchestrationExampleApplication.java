package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.orchestration.delegation.AgentDelegation;
import io.github.ivannavas.sprout.orchestration.handoff.AgentHandoff;
import io.github.ivannavas.sprout.orchestration.orchestrator.AgentOrchestrator;

import java.util.List;
import java.util.Objects;

/**
 * One runnable tour of {@code sprout-orchestration}'s three multi-agent patterns, sharing a single cast
 * of agents (a math and a history specialist, plus a supervisor and a triage front desk):
 *
 * <ol>
 *   <li><b>Concurrent orchestration</b> — fan several prompts out to one agent at once with
 *       {@link AgentOrchestrator} and collect the results by id.</li>
 *   <li><b>Delegation</b> — a supervisor calls specialists as tools via {@link AgentDelegation} and
 *       composes the reply; control stays with the supervisor.</li>
 *   <li><b>Hand-off</b> — a triage agent transfers the conversation to a specialist via
 *       {@link AgentHandoff}; control passes and the specialist produces the final answer.</li>
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

        concurrentOrchestration(history);
        delegation(supervisor, math, history);
        handoff(triage, math, history);
    }

    /** 1. Fan independent questions out to one agent concurrently and read the answers back by id. */
    private static void concurrentOrchestration(AgentExecutor history) {
        System.out.println("== Concurrent orchestration ==");
        List<String> questions = List.of(
                "Tell me about the Mona Lisa",
                "Tell me about the Moon",
                "Tell me about Rome");

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(history)) {
            for (String question : questions) {
                orchestrator.execute(question, question, question);
            }
            orchestrator.waitForExecutions();

            for (String question : questions) {
                System.out.println("  " + question + " -> " + answerOf(orchestrator.getResult(question).block()));
            }
        }
    }

    /** 2. A supervisor delegates each question to a specialist tool and composes the reply. */
    private static void delegation(AgentExecutor supervisor, AgentExecutor math, AgentExecutor history) {
        System.out.println("== Delegation ==");
        AgentDelegation.builder()
                .specialist("math", "Solves arithmetic and number problems.", math)
                .specialist("history", "Answers history and general-knowledge questions.", history)
                .attachTo(supervisor);

        for (String question : List.of("What is 6 times 7?", "Who painted the Mona Lisa?")) {
            String answer = supervisor.execute("delegation-" + question, question).response();
            System.out.println("  " + question + " -> " + answer);
        }
    }

    /** 3. A triage agent hands the conversation off to a specialist, which takes over and answers. */
    private static void handoff(AgentExecutor triage, AgentExecutor math, AgentExecutor history) {
        System.out.println("== Hand-off ==");
        AgentHandoff team = AgentHandoff.builder()
                .member("triage", "First point of contact; routes the user.", triage)
                .member("math", "Solves arithmetic and number problems.", math)
                .member("history", "Answers history and general-knowledge questions.", history)
                .build();

        for (String request : List.of("What is 8 times 9?", "Who painted the Mona Lisa?")) {
            AgentHandoff.HandoffResult result = team.run(request);
            System.out.println("  " + request);
            System.out.println("    path:   " + String.join(" -> ", result.path()));
            System.out.println("    answer: " + result.response());
        }
    }

    private static String answerOf(io.github.ivannavas.sprout.model.AgentResult result) {
        return Objects.requireNonNull(result).response();
    }
}
