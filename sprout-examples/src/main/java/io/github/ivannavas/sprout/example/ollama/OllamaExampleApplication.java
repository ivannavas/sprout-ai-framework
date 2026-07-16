package io.github.ivannavas.sprout.example.ollama;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.StreamListener;

import java.util.List;

/**
 * Talks to a locally running <a href="https://ollama.com">Ollama</a> server — no API key, the model
 * runs on your machine. Start Ollama and pull a model first, e.g. {@code ollama pull llama3.2}; the
 * model name is read from {@code ollama.model.name} (default {@code llama3.2}, override with the
 * {@code OLLAMA_MODEL} env var).
 */
public final class OllamaExampleApplication {

    // Scan this example's package plus the sprout-ollama module so the @Model("ollama") executor is
    // registered. The `ollama` Maven profile supplies this via -Dsprout.scan.base-packages; default it
    // here too so a plain IDE run (no VM flags) works. Any value already set wins — we only fill the gap.
    private static final String DEFAULT_SCAN_PACKAGES =
            "io.github.ivannavas.sprout.example.ollama,io.github.ivannavas.sprout.ollama";

    public static void main(String[] args) {
        if (System.getProperty("sprout.scan.base-packages") == null) {
            System.setProperty("sprout.scan.base-packages", DEFAULT_SCAN_PACKAGES);
        }

        SproutContainer container = SproutApplication.run(OllamaExampleApplication.class);

        // 1. A direct, streaming model call — Ollama replies token by token, printed as it arrives.
        ModelExecutor model = container.getSingleton("ollama");
        System.out.print("Streaming a haiku about Java: ");
        model.chatStream(
                new ModelRequest(List.of(Message.user("Write a haiku about the Java programming language."))),
                new StreamListener() {
                    @Override
                    public void onToken(String token) {
                        System.out.print(token);
                    }

                    @Override
                    public void onComplete(ModelResponse response) {
                        System.out.println();
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.out.println("\n[stream failed] " + error.getMessage());
                    }
                });

        // 2. The same model wrapped in an agent, answering a couple of one-shot questions.
        AgentExecutor assistant = container.getSingleton("localAssistantAgentExecutor");
        for (String question : List.of("Name a planet in our solar system.", "Name an ocean on Earth.")) {
            // Each question gets its own conversation id so they don't share history.
            System.out.println(question + " -> " + assistant.execute(question, question).response());
        }
    }
}
