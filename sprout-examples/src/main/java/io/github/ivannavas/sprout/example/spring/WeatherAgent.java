package io.github.ivannavas.sprout.example.spring;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * A Sprout agent. Its {@code @Tool} is backed by a Spring {@code @Service} that Sprout injects via
 * the starter's external bean resolver (as a lazy proxy) — here through the constructor.
 *
 * <p>As the agent extends {@link AgentExecutor}, the instance is registered as the Spring bean
 * {@code weatherAgentExecutor}, which is what {@link WeatherController} injects. Its conversation
 * history is persisted by {@link JpaConversationStore} (another Spring bean Sprout resolves).
 */
@Agent(
        model = StubChatModel.class,
        conversationStore = JpaConversationStore.class,
        systemPrompt =  "You are a helpful weather assistant.",
        maxIterations = 4
)
public class WeatherAgent extends AgentExecutor {

    private final WeatherService weatherService;

    @Autowired
    public WeatherAgent(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(name = "lookup", description = "Look up the weather forecast for a city")
    public String lookup(String city) {
        return weatherService.forecast(city);
    }
}
