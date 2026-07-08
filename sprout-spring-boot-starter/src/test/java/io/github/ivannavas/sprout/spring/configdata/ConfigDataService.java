package io.github.ivannavas.sprout.spring.configdata;

import io.github.ivannavas.sprout.annotation.Service;
import io.github.ivannavas.sprout.annotation.Value;

// A Sprout component whose @Value points at a property that lives only in a Spring config-data file.
@Service
public class ConfigDataService {

    @Value("${configdata.value}")
    private String value;

    public String value() {
        return value;
    }
}
