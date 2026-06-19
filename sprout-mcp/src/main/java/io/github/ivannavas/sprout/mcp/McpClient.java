package io.github.ivannavas.sprout.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import io.github.ivannavas.sprout.model.ToolResult;
import io.github.ivannavas.sprout.tool.ToolProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects to an MCP server as a client and exposes its tools as a {@link ToolProvider}, so a
 * Sprout agent can call them. Speaks newline-delimited JSON-RPC 2.0 over stdio.
 *
 * <p>Created either from a launch command (the server runs as a child process) or, for in-process
 * use and testing, from a pair of streams. The connection (and, for the command form, the child
 * process) is established lazily on first use.
 */
public final class McpClient implements ToolProvider {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final String name;
    private final List<String> command;

    private Process process;
    private BufferedReader in;
    private PrintStream out;
    private List<ToolDefinition> tools;

    /** Connects by launching {@code command} as a child process speaking MCP over its stdio. */
    public McpClient(String name, List<String> command) {
        this.name = name;
        this.command = List.copyOf(command);
    }

    /** Connects to an already-running server over the given streams (mainly for in-process tests). */
    McpClient(String name, InputStream serverOut, OutputStream serverIn) {
        this.name = name;
        this.command = null;
        this.in = new BufferedReader(new InputStreamReader(serverOut, StandardCharsets.UTF_8));
        this.out = new PrintStream(serverIn, true, StandardCharsets.UTF_8);
    }

    public String name() {
        return name;
    }

    @Override
    public synchronized List<ToolDefinition> tools() {
        ensureConnected();
        return tools;
    }

    @Override
    public synchronized ToolResult call(ToolCall call) {
        try {
            ensureConnected();
            ObjectNode params = json.createObjectNode();
            params.put("name", call.name());
            params.set("arguments", json.readTree(
                    call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson()));

            JsonNode result = rpc("tools/call", params);
            String text = textOf(result);
            return result.path("isError").asBoolean(false)
                    ? ToolResult.failure(call.id(), text)
                    : ToolResult.ok(call.id(), text);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolResult.failure(call.id(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private void ensureConnected() {
        if (tools != null) {
            return;
        }
        try {
            if (out == null) {
                startProcess();
            }
            handshake();
            tools = fetchTools();
        } catch (IOException e) {
            throw new IllegalStateException("Sprout MCP: failed connecting to server '" + name + "'", e);
        }
    }

    private void startProcess() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT); // surface the server's logs/errors
        process = builder.start();
        in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintStream(process.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    private void handshake() throws IOException {
        ObjectNode init = json.createObjectNode();
        init.put("protocolVersion", PROTOCOL_VERSION);
        init.putObject("capabilities");
        init.putObject("clientInfo").put("name", "sprout-mcp-client").put("version", "1.0.0");
        rpc("initialize", init);

        // Notification: no id, no response expected.
        ObjectNode initialized = json.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        out.println(json.writeValueAsString(initialized));
    }

    private List<ToolDefinition> fetchTools() throws IOException {
        JsonNode result = rpc("tools/list", json.createObjectNode());
        List<ToolDefinition> definitions = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            definitions.add(new ToolDefinition(
                    tool.path("name").asText(),
                    tool.path("description").asText(""),
                    tool.path("inputSchema").toString()));
        }
        return definitions;
    }

    private JsonNode rpc(String method, JsonNode params) throws IOException {
        int id = nextId.getAndIncrement();
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        out.println(json.writeValueAsString(request));

        // Read until the response matching our id arrives, skipping any notifications.
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message = json.readTree(line);
            if (message.path("id").asInt(-1) == id) {
                if (message.has("error")) {
                    throw new IOException("MCP server error: " + message.path("error").path("message").asText());
                }
                return message.path("result");
            }
        }
        throw new IOException("MCP server closed the connection before responding to '" + method + "'");
    }

    private String textOf(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText("");
        }
        return "";
    }

    @Override
    public synchronized void close() {
        if (out != null) {
            out.close();
        }
        if (process != null) {
            process.destroy();
        }
    }
}
