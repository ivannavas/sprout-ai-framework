package io.github.ivannavas.sprout.mcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an {@link io.github.ivannavas.sprout.annotation.Agent @Agent} connects to one or
 * more MCP servers as a client, making their tools callable by the agent's model alongside its own
 * {@code @Tool} methods.
 *
 * <pre>{@code
 * @Agent(model = MyModel.class)
 * @UseMcp(@McpEndpoint(command = {"${java.home}/bin/java", "-cp", "${java.class.path}", "com.example.Server"}))
 * public class MyAgent { ... }
 * }</pre>
 *
 * <p>Must be placed on a class that is also an {@code @Agent}. Connections are opened lazily the
 * first time the agent runs and closed when the container shuts down.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UseMcp {

    McpEndpoint[] value();
}
