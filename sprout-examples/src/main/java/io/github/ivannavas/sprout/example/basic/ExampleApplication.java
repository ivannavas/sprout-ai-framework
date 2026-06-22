package io.github.ivannavas.sprout.example.basic;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.orchestration.orchestrator.AgentOrchestrator;

import java.util.List;
import java.util.Objects;

public final class ExampleApplication {

    // This example must scan its own package plus the sprout-anthropic module so the
    // @Model("anthropic") executor is registered. The `basic` Maven profile supplies this via
    // -Dsprout.scan.base-packages; default it here too so a plain IDE run (no VM flags) works.
    // Any value already set (-D flag or the Maven profile) wins, since we only fill the gap.
    private static final String DEFAULT_SCAN_PACKAGES =
            "io.github.ivannavas.sprout.example.basic,io.github.ivannavas.sprout.anthropic";

    public static void main(String[] args) {
        if (System.getProperty("sprout.scan.base-packages") == null) {
            System.setProperty("sprout.scan.base-packages", DEFAULT_SCAN_PACKAGES);
        }

        SproutContainer container = SproutApplication.run(ExampleApplication.class);

        GreetingService greetingService = container.getSingleton(GreetingService.class);
        System.out.println(greetingService.greet("Sprout"));

        // Orchestration: fan several questions out to one agent concurrently instead of asking the
        // model one after another. Each run gets its own session and is read back by its id.
        AgentExecutor assistant = container.getSingleton("assistantAgentExecutor");
        List<String> questions = List.of(
                "Name a planet in our solar system.",
                "Name an ocean on Earth.",
                "Name a primary color.");

        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(assistant)) {
            for (String question : questions) {
                orchestrator.execute(question, question, question);
            }
            orchestrator.waitForExecutions();

            for (String question : questions) {
                String answer = Objects.requireNonNull(orchestrator.getResult(question).block()).response();
                System.out.println(question + " -> " + answer);
            }
        }
    }
}
