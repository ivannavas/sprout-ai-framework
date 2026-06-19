package io.github.ivannavas.sprout.mcp;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.mcp.annotation.McpEndpoint;
import io.github.ivannavas.sprout.mcp.annotation.UseMcp;
import io.github.ivannavas.sprout.model.ComponentAnnotation;
import io.github.ivannavas.sprout.processor.AgentProcessor;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Replaces the core {@link AgentProcessor} when {@code sprout-mcp} is on the classpath (via
 * {@code @Processor(overrides = ...)}). Builds the {@link AgentExecutor} the same way, then — when the
 * class also carries {@link UseMcp @UseMcp} — attaches the declared MCP servers as tool providers
 * during the process phase, once the executor already exists. A reference example of one processor
 * overriding another.
 */
@Processor(value = Agent.class, overrides = AgentProcessor.class)
public class McpAgentProcessor extends AgentProcessor {

    public McpAgentProcessor(Class<?> component, SproutContainer sproutContainer) {
        super(component, sproutContainer);
        putComponentAnnotations(Map.of(UseMcp.class, new ComponentAnnotation(this::connectMcpServers)));
    }

    private void connectMcpServers(Annotation annotation) {
        UseMcp useMcp = (UseMcp) annotation;

        Object executorBean = sproutContainer.getSingleton(AgentProcessor.executorBeanName(component));
        if (!(executorBean instanceof AgentExecutor executor)) {
            throw new IllegalStateException("@UseMcp on " + component + " requires the class to also be an @Agent");
        }

        List<McpClient> clients = new ArrayList<>();
        for (McpEndpoint endpoint : useMcp.value()) {
            List<String> command = Arrays.stream(endpoint.command())
                    .map(sproutContainer::resolveExpression)
                    .toList();
            String name = endpoint.name().isEmpty() ? command.toString() : endpoint.name();
            McpClient client = new McpClient(name, command);
            clients.add(client);
            executor.addToolProvider(client);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> clients.forEach(McpClient::close), "sprout-mcp-client-close"));
        sproutContainer.logger().info("Sprout MCP: agent " + component.getSimpleName()
                + " connected to " + clients.size() + " MCP server(s)");
    }
}
