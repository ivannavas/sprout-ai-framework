package io.github.ivannavas.sprout.example.basic;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.anthropic.executor.AnthropicModelExecutor;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * A minimal agent backed by the Anthropic model. The basic example fans several questions out to this
 * one agent concurrently with an {@code AgentOrchestrator}, so each question hits the API on its own
 * worker thread instead of one after another.
 */
@Agent(
        model = AnthropicModelExecutor.class,
        systemPrompt = "You are a concise assistant. Answer in one short sentence."
)
public class AssistantAgent extends AgentExecutor {
}
