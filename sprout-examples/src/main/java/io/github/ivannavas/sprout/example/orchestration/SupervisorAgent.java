package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * The delegation supervisor. It has no {@code @Tool} methods of its own — the specialist agents are
 * attached at runtime as an {@code AgentDelegation} in {@link OrchestrationExampleApplication}.
 */
@Agent(
        model = SupervisorModel.class,
        systemPrompt = "You are a supervisor. Route each question to the right specialist.",
        maxIterations = 4
)
public class SupervisorAgent extends AgentExecutor {
}
