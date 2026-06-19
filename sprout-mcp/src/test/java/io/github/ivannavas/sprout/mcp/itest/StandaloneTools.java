package io.github.ivannavas.sprout.mcp.itest;

import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;

/** A plain {@code @Mcp} bean: tools exposed over MCP, no agent involved. */
@Mcp(name = "standalone", version = "1.0")
public class StandaloneTools {

    @Tool(name = "hello", description = "Greet someone")
    public String hello(String who) {
        return "hi " + who;
    }
}
