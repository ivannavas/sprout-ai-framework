package io.github.ivannavas.sprout.example.events;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/** A tiny agent whose single {@code forecast} tool the stub model calls, so the loop emits a tool event. */
@Agent(
        model = WeatherStubModel.class,
        systemPrompt = "You are a concise weather assistant."
)
public class WeatherAgent extends AgentExecutor {

    @Tool(description = "Look up the weather forecast for a city")
    public String forecast(String city) {
        return "Sunny, 25°C in " + city;
    }
}
