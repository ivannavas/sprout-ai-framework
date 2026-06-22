package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * The hand-off entry agent (front desk). It transfers the conversation to the supervisor, which then
 * delegates to a specialist; the {@code handoff_to_*} tools are wired in at runtime by the
 * {@code AgentHandoff} team in {@link OrchestrationExampleApplication}.
 */
@Agent(
        model = TriageModel.class,
        conversationStore = TeamConversationStore.class,
        systemPrompt = "You are the front desk. Route each request to the right specialist.",
        maxIterations = 4
)
public class TriageAgent extends AgentExecutor {
}
