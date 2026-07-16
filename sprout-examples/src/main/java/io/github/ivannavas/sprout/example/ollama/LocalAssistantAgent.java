package io.github.ivannavas.sprout.example.ollama;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.ollama.executor.OllamaModelExecutor;

/**
 * A minimal agent backed by a locally hosted Ollama model — no API key, everything runs on the
 * machine. The model name comes from {@code ollama.model.name} (see {@code sprout.properties}).
 */
@Agent(
        model = OllamaModelExecutor.class,
        systemPrompt = "You are a concise assistant. Answer in one short sentence."
)
public class LocalAssistantAgent extends AgentExecutor {
}
