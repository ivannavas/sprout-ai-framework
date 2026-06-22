package io.github.ivannavas.sprout.example.spring;

import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.orchestration.orchestrator.AgentOrchestrator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A regular Spring {@code @RestController} that injects a Sprout-built {@link AgentExecutor} bean —
 * demonstrating Sprout beans flowing into Spring components.
 *
 * <p>{@code /weather} runs the agent for a single city. {@code /weather/batch} shows
 * {@code sprout-orchestration}: it wraps the same agent in an {@link AgentOrchestrator} and fans the
 * cities out concurrently — one agent run per city, each on its own session — then collects every
 * forecast once the batch finishes.
 */
@RestController
public class WeatherController {

    private final AgentExecutor weatherAgent;

    public WeatherController(@Qualifier("weatherAgentExecutor") AgentExecutor weatherAgent) {
        this.weatherAgent = weatherAgent;
    }

    @GetMapping("/weather")
    public String weather(@RequestParam(defaultValue = "Madrid") String city) {
        return weatherAgent.execute("web-session", city).response();
    }

    @GetMapping("/weather/batch")
    public Map<String, String> weatherBatch(
            @RequestParam(defaultValue = "Madrid,Paris,London") List<String> cities) {
        try (AgentOrchestrator orchestrator = AgentOrchestrator.of(weatherAgent)) {
            for (String city : cities) {
                orchestrator.execute(city, city, "batch-" + city);
            }
            orchestrator.waitForExecutions();

            Map<String, String> forecasts = new LinkedHashMap<>();
            for (String city : cities) {
                forecasts.put(city, Objects.requireNonNull(orchestrator.getResult(city).block()).response());
            }
            return forecasts;
        }
    }
}
