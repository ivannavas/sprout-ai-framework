package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * A specialist agent that handles arithmetic, backed by the offline {@link MathModel}. It is reused as
 * both a delegation specialist and a hand-off team member in {@link OrchestrationExampleApplication}.
 */
@Agent(
        model = MathModel.class,
        conversationStore = TeamConversationStore.class,
        systemPrompt = "You are a math specialist. Solve the arithmetic in the conversation."
)
public class MathSpecialist extends AgentExecutor {
}
