package io.github.ivannavas.sprout.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.annotation.ToolParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolReflectionTest {

    enum Unit { CELSIUS, FAHRENHEIT }

    static class Tools {
        @Tool(description = "Forecast")
        String forecast(@ToolParam(description = "City name") String city,
                        @ToolParam(description = "Temperature unit") Unit unit,
                        @ToolParam(required = false) Integer days,
                        List<String> extras) {
            return city + days + unit + extras;
        }
    }

    private final ObjectMapper json = new ObjectMapper();

    private Method forecast() throws NoSuchMethodException {
        return Tools.class.getDeclaredMethod("forecast", String.class, Unit.class, Integer.class, List.class);
    }

    @Test
    void schemaCarriesDescriptionsTypesAndRequiredFlags() throws Exception {
        JsonNode schema = json.readTree(ToolReflection.schemaFor(forecast()));
        JsonNode props = schema.path("properties");

        assertEquals("string", props.path("city").path("type").asText());
        assertEquals("City name", props.path("city").path("description").asText());

        // Required parameters are listed; the optional one is not.
        List<String> required = json.convertValue(schema.path("required"), List.class);
        assertTrue(required.contains("city"));
        assertTrue(required.contains("unit"));
        assertTrue(required.contains("extras"));
        assertFalse(required.contains("days"), "@ToolParam(required = false) should be optional");
    }

    @Test
    void enumsBecomeConstrainedStrings() throws Exception {
        JsonNode unit = json.readTree(ToolReflection.schemaFor(forecast())).path("properties").path("unit");
        assertEquals("string", unit.path("type").asText());
        List<String> values = json.convertValue(unit.path("enum"), List.class);
        assertEquals(List.of("CELSIUS", "FAHRENHEIT"), values);
    }

    @Test
    void collectionsBecomeArrays() throws Exception {
        JsonNode extras = json.readTree(ToolReflection.schemaFor(forecast())).path("properties").path("extras");
        assertEquals("array", extras.path("type").asText());
        assertEquals("string", extras.path("items").path("type").asText());
    }

    @Test
    void invokeBindsGenericAndEnumArguments() throws Exception {
        Tools tools = new Tools();
        Object result = ToolReflection.invoke(tools, forecast(),
                "{\"city\":\"Madrid\",\"unit\":\"CELSIUS\",\"days\":3,\"extras\":[\"wind\",\"uv\"]}");
        assertEquals("Madrid3CELSIUS[wind, uv]", result);
    }
}
