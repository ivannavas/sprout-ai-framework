package io.github.ivannavas.sprout.example.spring;

import io.github.ivannavas.sprout.model.Message;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises both integration directions at once:
 * <ul>
 *   <li>the Spring {@link WeatherController} injects a Sprout-built {@code AgentExecutor};</li>
 *   <li>the Sprout {@link WeatherAgent} tool calls the Spring {@link WeatherService};</li>
 *   <li>the agent's history is persisted to H2 through the Spring {@link JpaConversationStore}.</li>
 * </ul>
 */
@SpringBootTest
class WeatherEndToEndTest {

    @Autowired
    WeatherController controller;

    @Autowired
    JpaConversationStore conversationStore;

    @Test
    void agentRunsToolBackedBySpringService() {
        String result = controller.weather("Madrid");
        assertThat(result)
                .contains("Madrid")
                .contains("sunny");
    }

    @Test
    void batchEndpointFansCitiesOutConcurrently() {
        Map<String, String> forecasts = controller.weatherBatch(List.of("Madrid", "Paris", "London"));

        assertThat(forecasts).containsOnlyKeys("Madrid", "Paris", "London");
        assertThat(forecasts.get("Madrid")).contains("Madrid").contains("sunny");
        assertThat(forecasts.get("Paris")).contains("Paris");
        assertThat(forecasts.get("London")).contains("London");
    }

    @Test
    void conversationIsPersistedToH2AndReloaded() {
        conversationStore.clear("web-session");

        controller.weather("Madrid");
        List<Message> afterFirst = conversationStore.load("web-session");
        assertThat(afterFirst).isNotEmpty();

        // A second turn reloads the persisted history and appends to it.
        controller.weather("Barcelona");
        assertThat(conversationStore.load("web-session")).hasSizeGreaterThan(afterFirst.size());
    }
}
