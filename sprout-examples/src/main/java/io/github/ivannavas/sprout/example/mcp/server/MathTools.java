package io.github.ivannavas.sprout.example.mcp.server;

import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;

/** A plain {@code @Mcp} component: its {@code @Tool} methods are published to MCP clients. */
@Mcp(name = "sprout-math", version = "1.0.0")
public class MathTools {

    @Tool(name = "add", description = "Add two numbers")
    public double add(double a, double b) {
        return a + b;
    }

    @Tool(name = "multiply", description = "Multiply two numbers")
    public double multiply(double a, double b) {
        return a * b;
    }
}
