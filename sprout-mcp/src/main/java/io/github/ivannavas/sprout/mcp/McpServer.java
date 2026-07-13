package io.github.ivannavas.sprout.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A <a href="https://modelcontextprotocol.io">Model Context Protocol</a> server that exposes the
 * {@link Tool @Tool} methods of every {@link Mcp @Mcp} bean managed by a {@link SproutContainer}.
 *
 * <p>Build it with {@link #from(SproutContainer)} and serve it over either transport: the standard
 * stdio transport with {@link #serveStdio()} (newline-delimited JSON-RPC 2.0), or HTTP with
 * {@link #serveHttp(int)} (the MCP Streamable HTTP transport), so remote clients can reach it by URL.
 * The supported methods are {@code initialize}, {@code tools/list} and {@code tools/call}.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, McpTool> toolsByName = new LinkedHashMap<>();
    private String name = "sprout-mcp";
    private String version = "1.0.0";
    private boolean identitySet;

    public McpServer() {
    }

    /**
     * Discovers every {@code @Mcp} bean in the container and aggregates their {@code @Tool} methods
     * into a single server. Equivalent to creating an empty server and {@link #register(Object)}-ing
     * each bean; useful when wiring the server manually rather than via {@link McpProcessor}.
     *
     * @throws IllegalStateException if two tools resolve to the same name
     */
    public static McpServer from(SproutContainer container) {
        McpServer server = new McpServer();
        for (Object bean : distinctBeans(container.getSingletonsByName().values())) {
            server.register(bean);
        }
        return server;
    }

    /**
     * Registers a single {@code @Mcp}-annotated bean, adding all of its {@code @Tool} methods to the
     * server. Beans without {@code @Mcp} are ignored. The server name/version are adopted from the
     * first registered bean, falling back to the annotation defaults.
     *
     * @throws IllegalStateException if a tool name collides with one already registered
     */
    public void register(Object bean) {
        Mcp mcp = bean.getClass().getAnnotation(Mcp.class);
        if (mcp == null) {
            return;
        }
        if (!identitySet) {
            name = mcp.name();
            version = mcp.version();
            identitySet = true;
        }
        for (McpTool tool : collectTools(bean)) {
            McpTool previous = toolsByName.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("Duplicate MCP tool name '" + tool.name() + "' from "
                        + previous.bean().getClass().getName() + " and " + bean.getClass().getName());
            }
        }
    }

    private static List<Object> distinctBeans(Collection<Object> beans) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> distinct = new ArrayList<>();
        for (Object bean : beans) {
            if (bean != null && seen.add(bean)) {
                distinct.add(bean);
            }
        }
        return distinct;
    }

    private static List<McpTool> collectTools(Object bean) {
        List<McpTool> tools = new ArrayList<>();
        for (Method method : bean.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                tools.add(McpTool.of(bean, method));
            }
        }
        return tools;
    }

    /** The tools this server exposes, keyed by their MCP name. */
    public Map<String, McpTool> tools() {
        return Map.copyOf(toolsByName);
    }

    /**
     * Serves the protocol over {@link System#in}/{@link System#out} until the input stream is
     * closed (EOF). Blocking; intended to be called from an application's {@code main}.
     */
    public void serveStdio() throws IOException {
        serveStdio(System.in, System.out);
    }

    void serveStdio(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintStream writer = new PrintStream(out, true, StandardCharsets.UTF_8);
        String line;
        while ((line = reader.readLine()) != null) {
            // Strip a leading UTF-8 BOM that some platforms prepend to the first line.
            if (!line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1);
            }
            if (line.isBlank()) {
                continue;
            }
            JsonNode response = handle(json.readTree(line));
            if (response != null) {
                writer.println(json.writeValueAsString(response));
            }
        }
    }

    /**
     * Serves the protocol over HTTP (the MCP Streamable HTTP transport) on {@code port}. Each JSON-RPC
     * message is accepted as a {@code POST} and answered with an {@code application/json} response;
     * notifications (no {@code id}) get {@code 202 Accepted} with no body. Pass {@code 0} to bind an
     * ephemeral port. Non-blocking: requests run on the returned server's background threads, which
     * keep the JVM alive until {@link HttpServer#stop(int)} is called.
     */
    public HttpServer serveHttp(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/", this::handleHttp);
        http.start();
        return http;
    }

    private void handleHttp(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            JsonNode response;
            JsonNode requestId = null;
            try {
                JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
                requestId = request.get("id");
                response = handle(request);
            } catch (Exception e) {
                writeJson(exchange, error(requestId, -32603, "Internal error: " + e.getMessage()));
                return;
            }

            if (response == null) {
                exchange.sendResponseHeaders(202, -1); // notification: accepted, no body
                return;
            }
            writeJson(exchange, response);
        }
    }

    private void writeJson(HttpExchange exchange, JsonNode message) throws IOException {
        byte[] body = json.writeValueAsBytes(message);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    /**
     * Handles a single JSON-RPC request and returns the response, or {@code null} for
     * notifications (requests without an {@code id}) that take no reply.
     */
    JsonNode handle(JsonNode request) {
        JsonNode id = request.get("id");
        String method = request.path("method").asText("");

        // Notifications carry no id and never get a response.
        if (id == null || id.isNull()) {
            return null;
        }

        return switch (method) {
            case "initialize" -> success(id, initializeResult());
            case "tools/list" -> success(id, toolsListResult());
            case "tools/call" -> toolsCall(id, request.path("params"));
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = json.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", name);
        serverInfo.put("version", version);
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = json.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (McpTool tool : toolsByName.values()) {
            ObjectNode node = tools.addObject();
            node.put("name", tool.name());
            node.put("description", tool.description());
            try {
                node.set("inputSchema", json.readTree(tool.inputSchemaJson()));
            } catch (IOException e) {
                throw new IllegalStateException("Invalid tool schema for " + tool.name(), e);
            }
        }
        return result;
    }

    private JsonNode toolsCall(JsonNode id, JsonNode params) {
        String toolName = params.path("name").asText("");
        McpTool tool = toolsByName.get(toolName);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + toolName);
        }

        JsonNode arguments = params.get("arguments");
        String argumentsJson = arguments == null || arguments.isNull() ? "{}" : arguments.toString();
        try {
            Object result = tool.invoke(argumentsJson);
            return success(id, toolCallResult(text(result), false));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return success(id, toolCallResult(cause.getClass().getSimpleName() + ": " + cause.getMessage(), true));
        }
    }

    private ObjectNode toolCallResult(String text, boolean isError) {
        ObjectNode result = json.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private String text(Object result) throws IOException {
        if (result == null) {
            return "done";
        }
        if (result instanceof String s) {
            return s;
        }
        return json.writeValueAsString(result);
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = newResponse(id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = newResponse(id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private ObjectNode newResponse(JsonNode id) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        return response;
    }
}
