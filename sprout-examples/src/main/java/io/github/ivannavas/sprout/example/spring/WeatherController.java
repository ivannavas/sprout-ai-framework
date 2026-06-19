package io.github.ivannavas.sprout.example.spring;

import io.github.ivannavas.sprout.executor.AgentExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A regular Spring {@code @RestController} that injects a Sprout-built {@link AgentExecutor} bean —
 * demonstrating Sprout beans flowing into Spring components.
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
}
