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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects to an MCP server as a client and exposes its tools as a {@link ToolProvider}, so a
 * Sprout agent can call them. Speaks JSON-RPC 2.0 over one of two transports:
 *
 * <ul>
 *   <li><b>stdio</b> — the server runs as a child process launched from a command, communicating over
 *       its standard input/output. Use this for local servers Sprout should manage.</li>
 *   <li><b>HTTP</b> — the client connects to an already-running server at a URL (the MCP Streamable
 *       HTTP transport). Use this for remote or independently-started servers.</li>
 * </ul>
 *
 * <p>A third constructor takes a pair of streams for in-process use and testing. The connection (and,
 * for the command form, the child process) is established lazily on first use.
 */
public final class McpClient implements ToolProvider {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final String name;
    private final List<String> command; // stdio child-process form (nullable)
    private final String url;            // HTTP form (nullable)
    private Transport transport;         // eagerly set for the stream form, else created lazily
    private List<ToolDefinition> tools;

    /** Connects by launching {@code command} as a child process speaking MCP over its stdio. */
    public McpClient(String name, List<String> command) {
        this.name = name;
        this.command = List.copyOf(command);
        this.url = null;
    }

    /** Connects to an already-running server over HTTP (MCP Streamable HTTP transport). */
    public McpClient(String name, String url) {
        this.name = name;
        this.command = null;
        this.url = url;
    }

    /** Connects to an already-running server over the given streams (mainly for in-process tests). */
    McpClient(String name, InputStream serverOut, OutputStream serverIn) {
        this.name = name;
        this.command = null;
        this.url = null;
        this.transport = new StdioTransport(null, serverOut, serverIn);
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
            if (transport == null) {
                transport = url != null ? new HttpTransport(url) : StdioTransport.launch(command);
            }
            handshake();
            tools = fetchTools();
        } catch (IOException e) {
            throw new IllegalStateException("Sprout MCP: failed connecting to server '" + name + "'", e);
        }
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
        transport.sendNotification(json.writeValueAsString(initialized));
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

        JsonNode message = transport.sendRequest(json.writeValueAsString(request), id);
        if (message.has("error")) {
            throw new IOException("MCP server error: " + message.path("error").path("message").asText());
        }
        return message.path("result");
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
        if (transport != null) {
            transport.close();
        }
    }

    /**
     * Carries JSON-RPC messages to the server and back. A request (one that carries an {@code id})
     * expects the matching response message; a notification is fire-and-forget.
     */
    private interface Transport {

        /** Sends a request line and returns the full JSON-RPC response message matching {@code id}. */
        JsonNode sendRequest(String message, int id) throws IOException;

        /** Sends a notification; no response is read. */
        void sendNotification(String message) throws IOException;

        void close();
    }

    /**
     * Newline-delimited JSON-RPC over a pair of streams — either a launched child process or streams
     * supplied directly (tests, in-process servers).
     */
    private static final class StdioTransport implements Transport {

        private final ObjectMapper json = new ObjectMapper();
        private final Process process; // null when the streams were supplied directly
        private final BufferedReader in;
        private final PrintStream out;

        StdioTransport(Process process, InputStream serverOut, OutputStream serverIn) {
            this.process = process;
            this.in = new BufferedReader(new InputStreamReader(serverOut, StandardCharsets.UTF_8));
            this.out = new PrintStream(serverIn, true, StandardCharsets.UTF_8);
        }

        static StdioTransport launch(List<String> command) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT); // surface the server's logs/errors
            Process process = builder.start();
            return new StdioTransport(process, process.getInputStream(), process.getOutputStream());
        }

        @Override
        public JsonNode sendRequest(String message, int id) throws IOException {
            out.println(message);
            // Read until the response matching our id arrives, skipping any notifications.
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode response = json.readTree(line);
                if (response.path("id").asInt(-1) == id) {
                    return response;
                }
            }
            throw new IOException("MCP server closed the connection before responding");
        }

        @Override
        public void sendNotification(String message) {
            out.println(message);
        }

        @Override
        public void close() {
            out.close();
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * MCP Streamable HTTP transport: each JSON-RPC message is POSTed to the endpoint. The server may
     * answer with a single {@code application/json} body or a {@code text/event-stream} (SSE) carrying
     * the response. A {@code Mcp-Session-Id} handed back on initialize is echoed on later requests.
     */
    private static final class HttpTransport implements Transport {

        private final ObjectMapper json = new ObjectMapper();
        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final URI uri;
        private volatile String sessionId;

        HttpTransport(String url) {
            this.uri = URI.create(url);
        }

        @Override
        public JsonNode sendRequest(String message, int id) throws IOException {
            HttpResponse<String> response = post(message);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("MCP HTTP error " + status + ": " + response.body());
            }
            captureSession(response);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("text/event-stream")) {
                return parseSse(response.body(), id);
            }
            return json.readTree(response.body());
        }

        @Override
        public void sendNotification(String message) throws IOException {
            HttpResponse<String> response = post(message);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("MCP HTTP error " + status + ": " + response.body());
            }
            captureSession(response);
        }

        private HttpResponse<String> post(String message) throws IOException {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8));
                if (sessionId != null) {
                    builder.header("Mcp-Session-Id", sessionId);
                }
                return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling MCP server", e);
            }
        }

        private void captureSession(HttpResponse<?> response) {
            response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> this.sessionId = id);
        }

        // Pull the JSON-RPC message whose id matches ours out of the SSE 'data:' events.
        private JsonNode parseSse(String body, int id) throws IOException {
            for (String raw : body.split("\n")) {
                String line = raw.strip();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).strip();
                if (data.isEmpty()) {
                    continue;
                }
                JsonNode message = json.readTree(data);
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
            throw new IOException("MCP server SSE stream ended without a response to request " + id);
        }

        @Override
        public void close() {
            // Best-effort session termination; the server may not support it, and it must not block shutdown.
            if (sessionId != null) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .header("Mcp-Session-Id", sessionId)
                            .DELETE()
                            .build();
                    http.send(request, HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {
                    // nothing actionable during shutdown
                }
            }
            http.close();
        }
    }
}
