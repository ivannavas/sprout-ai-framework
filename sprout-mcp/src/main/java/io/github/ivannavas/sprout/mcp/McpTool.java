package io.github.ivannavas.sprout.mcp;

import io.github.ivannavas.sprout.tool.ToolReflection;

import java.lang.reflect.Method;

// A single tool exposed by an @Mcp bean: its MCP-facing name/description, the JSON-Schema for its
// arguments, and the bean/method to invoke.
public record McpTool(String name, String description, String inputSchemaJson, Object bean, Method method) {

    static McpTool of(Object bean, Method method) {
        method.setAccessible(true);
        return new McpTool(
                ToolReflection.toolName(method),
                ToolReflection.toolDescription(method),
                ToolReflection.schemaFor(method),
                bean,
                method);
    }

    Object invoke(String argumentsJson) throws Exception {
        return ToolReflection.invoke(bean, method, argumentsJson);
    }
}
