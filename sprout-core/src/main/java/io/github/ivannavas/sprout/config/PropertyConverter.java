package io.github.ivannavas.sprout.config;

// Coerces a string property value into a target field/parameter type.
public final class PropertyConverter {

    private PropertyConverter() {
    }

    public static Object convert(String value, Class<?> type) {
        if (type == String.class)                            return value;
        if (type == int.class || type == Integer.class)     return Integer.parseInt(value);
        if (type == long.class || type == Long.class)       return Long.parseLong(value);
        if (type == double.class || type == Double.class)   return Double.parseDouble(value);
        if (type == float.class || type == Float.class)     return Float.parseFloat(value);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == short.class || type == Short.class)     return Short.parseShort(value);
        if (type == byte.class || type == Byte.class)       return Byte.parseByte(value);
        throw new IllegalArgumentException("Unsupported property target type: " + type.getName());
    }
}
