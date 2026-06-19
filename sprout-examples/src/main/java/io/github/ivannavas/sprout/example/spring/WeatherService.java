package io.github.ivannavas.sprout.example.spring;

import org.springframework.stereotype.Service;

/**
 * A plain Spring {@code @Service}. It is managed by Spring, yet a Sprout {@code @Agent} depends on
 * it (see {@link WeatherAgent}) — demonstrating Spring beans flowing into Sprout components.
 */
@Service
public class WeatherService {

    public String forecast(String city) {
        return "Weather in " + city + ": sunny, 25°C";
    }
}
