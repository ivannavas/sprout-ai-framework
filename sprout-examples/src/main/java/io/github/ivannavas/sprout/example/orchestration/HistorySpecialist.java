package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * A specialist agent that answers history questions, backed by the offline {@link HistoryModel}. It is
 * reused across all three demos in {@link OrchestrationExampleApplication}: as the target of concurrent
 * orchestration, as a delegation specialist, and as a hand-off team member.
 */
@Agent(
        model = HistoryModel.class,
        conversationStore = TeamConversationStore.class,
        systemPrompt = "You are a history specialist. Answer the question in the conversation."
)
public class HistorySpecialist extends AgentExecutor {
}
