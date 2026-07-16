package io.github.ivannavas.sprout.spring.configdata;

import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Service;
import io.github.ivannavas.sprout.annotation.Value;

// A Sprout component whose @Value points at a property that lives only in a Spring config-data file.
@Service
public class ConfigDataService {

    private final String value;

    @Autowired
    public ConfigDataService(@Value("${configdata.value}") String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
