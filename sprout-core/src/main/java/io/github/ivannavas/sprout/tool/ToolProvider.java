package io.github.ivannavas.sprout.tool;

import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import io.github.ivannavas.sprout.model.ToolResult;

import java.util.List;

/**
 * A source of tools an agent can call beyond the {@code @Tool} methods declared on the agent class
 * itself — for instance a remote MCP server exposed as a client.
 *
 * <p>This lives in core so the agent executor can route tool calls to it without depending on any
 * particular transport; implementations (such as {@code McpClient}) live in their own modules.
 */
public interface ToolProvider extends AutoCloseable {

    /** The tools this provider exposes, as advertised to the model. */
    List<ToolDefinition> tools();

    /** Executes a tool call this provider owns and returns its result. */
    ToolResult call(ToolCall call);

    @Override
    default void close() {
    }
}
