package io.github.ivannavas.sprout.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

// Reflection helpers shared by the agent executor and the MCP server, so @Tool methods are turned
// into JSON-Schema definitions and invoked from a JSON payload identically on both sides.
public final class ToolReflection {

    private ToolReflection() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static String toolName(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        if (tool != null && !tool.name().isEmpty()) {
            return tool.name();
        }
        return method.getName();
    }

    public static String toolDescription(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        return tool != null ? tool.description() : "";
    }

    public static String schemaFor(Method method) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");

        for (Parameter parameter : method.getParameters()) {
            ObjectNode property = describe(parameter.getType(), parameter.getParameterizedType());
            ToolParam meta = parameter.getAnnotation(ToolParam.class);
            if (meta != null && !meta.description().isEmpty()) {
                property.put("description", meta.description());
            }
            properties.set(parameter.getName(), property);
            if (meta == null || meta.required()) {
                required.add(parameter.getName());
            }
        }
        return schema.toString();
    }

    // A null or blank payload is treated as no arguments. Each argument is bound to its parameter's
    // generic type, so List<String>, enums and POJOs deserialize correctly.
    public static Object invoke(Object bean, Method method, String argumentsJson) throws Exception {
        JsonNode args = JSON.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            JsonNode arg = args.get(parameters[i].getName());
            values[i] = arg == null || arg.isNull()
                    ? null
                    : JSON.convertValue(arg, JSON.getTypeFactory().constructType(parameters[i].getParameterizedType()));
        }
        return method.invoke(bean, values);
    }

    // Builds the JSON-Schema fragment for a single parameter type: enums become a constrained string,
    // arrays and collections become "array" with an "items" schema, everything else maps via jsonType.
    private static ObjectNode describe(Class<?> type, Type genericType) {
        ObjectNode node = JSON.createObjectNode();
        if (type.isEnum()) {
            node.put("type", "string");
            ArrayNode values = node.putArray("enum");
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
        } else if (type.isArray()) {
            node.put("type", "array");
            node.set("items", describe(type.getComponentType(), type.getComponentType()));
        } else if (Collection.class.isAssignableFrom(type)) {
            node.put("type", "array");
            Class<?> element = elementType(genericType);
            node.set("items", describe(element, element));
        } else {
            node.put("type", jsonType(type));
        }
        return node;
    }

    private static Class<?> elementType(Type genericType) {
        if (genericType instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return element;
            }
        }
        return String.class;
    }

    private static String jsonType(Class<?> type) {
        if (type == int.class || type == long.class || type == Integer.class || type == Long.class) return "integer";
        if (type == double.class || type == float.class || type == Double.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        return "string";
    }
}
