package io.github.ivannavas.sprout.orchestration.orchestrator;

import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.TokenUsage;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {

    /** Test executor that echoes a per-prompt canned answer, or throws for prompts marked to fail. */
    private static AgentExecutor executorReturning(Map<String, String> answers) {
        return new AgentExecutor() {
            @Override
            public AgentResult execute(String conversationId, String prompt) {
                String answer = answers.get(prompt);
                if (answer == null) {
                    throw new IllegalStateException("boom: " + prompt);
                }
                return new AgentResult(conversationId, answer, 1, TokenUsage.ZERO);
            }
        };
    }

    @Test
    void runsMultipleExecutionsConcurrentlyAndCollectsAll() {
        AgentExecutor executor = executorReturning(Map.of("a", "A", "b", "B", "c", "C"));
        AtomicInteger collected = new AtomicInteger();

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor, "session")) {
            orchestrator.execute("a", "a")
                    .execute("b", "b")
                    .execute("c", "c")
                    .then(executions -> collected.set(executions.size()))
                    .waitForExecutions();

            assertEquals(3, collected.get());
        }
    }

    @Test
    void resultIsRetrievableByIdAfterCompletion() {
        AgentExecutor executor = executorReturning(Map.of("hello", "world"));

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor, "session")) {
            orchestrator.execute("hello", "greeting").waitForExecutions();

            // Late read: the execution has already finished before we subscribe.
            AgentResult result = orchestrator.getResult("greeting").block(Duration.ofSeconds(2));
            assertEquals("world", result.response());
        }
    }

    @Test
    void supportsMultipleSubscribers() {
        AgentExecutor executor = executorReturning(Map.of("a", "A", "b", "B"));

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor, "session")) {
            orchestrator.execute("a", "a").execute("b", "b").waitForExecutions();

            assertEquals("A", orchestrator.getResult("a").block(Duration.ofSeconds(2)).response());
            assertEquals("B", orchestrator.getResult("b").block(Duration.ofSeconds(2)).response());
        }
    }

    @Test
    void failingExecutionDoesNotAffectOthers() {
        AgentExecutor executor = executorReturning(Map.of("ok", "fine"));

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor, "session")) {
            orchestrator.execute("ok", "ok")
                    .execute("missing", "bad") // not in the map -> throws
                    .waitForExecutions(2_000);

            assertEquals("fine", orchestrator.getResult("ok").block(Duration.ofSeconds(2)).response());
            assertThrows(IllegalStateException.class,
                    () -> orchestrator.getResult("bad").block(Duration.ofSeconds(2)));
        }
    }

    @Test
    void getExecutionsStreamsBothSuccessesAndFailures() {
        AgentExecutor executor = executorReturning(Map.of("ok", "fine"));

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor, "session")) {
            StepVerifier.create(orchestrator.getExecutions().take(2).collectList())
                    .then(() -> orchestrator.execute("ok", "ok").execute("missing", "bad"))
                    .assertNext(executions -> {
                        assertEquals(2, executions.size());
                        assertTrue(executions.stream().anyMatch(AgentOrchestrator.Execution::isSuccess));
                        assertTrue(executions.stream().anyMatch(e -> !e.isSuccess()));
                    })
                    .verifyComplete();
        }
    }

    @Test
    void executeWithoutSessionFails() {
        AgentExecutor executor = executorReturning(Map.of());
        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor)) {
            assertThrows(IllegalStateException.class, () -> orchestrator.execute("a", "a"));
        }
    }

    @Test
    void executeWithExplicitSessionPerCallIsolatesRuns() {
        // Echoes the conversation/session id back so each run's session is observable.
        AgentExecutor executor = new AgentExecutor() {
            @Override
            public AgentResult execute(String conversationId, String prompt) {
                return new AgentResult(conversationId, prompt + "@" + conversationId, 1, TokenUsage.ZERO);
            }
        };

        // No orchestrator-level session: each call supplies its own.
        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor)) {
            orchestrator.execute("p1", "a", "session-a")
                    .execute("p2", "b", "session-b")
                    .waitForExecutions(2_000);

            assertEquals("p1@session-a", orchestrator.getResult("a").block(Duration.ofSeconds(2)).response());
            assertEquals("p2@session-b", orchestrator.getResult("b").block(Duration.ofSeconds(2)).response());
        }
    }

    @Test
    void waitForSpecificExecutionIds() {
        AgentExecutor executor = executorReturning(Map.of("a", "A", "b", "B", "c", "C"));

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(executor, "session")) {
            orchestrator.execute("a", "a")
                    .execute("b", "b")
                    .execute("c", "c")
                    .waitForExecutions(List.of("a", "b"), 2_000);

            assertEquals("A", orchestrator.getResult("a").block(Duration.ofSeconds(2)).response());
            assertEquals("B", orchestrator.getResult("b").block(Duration.ofSeconds(2)).response());
        }
    }
}
