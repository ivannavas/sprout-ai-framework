package io.github.ivannavas.sprout.orchestration.delegation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import io.github.ivannavas.sprout.model.ToolResult;
import io.github.ivannavas.sprout.tool.ToolProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lets one agent delegate to others. It exposes a set of specialist {@link AgentExecutor}s to a
 * supervisor agent as {@link ToolProvider tools}, one per specialist. When the supervisor's model
 * decides a subtask belongs to a specialist, it "calls" that specialist like any other tool; this
 * provider runs the specialist with the given task and feeds its answer back as the tool result, so
 * the supervisor composes its final response from its team's work.
 *
 * <p>Because a specialist is exposed through the same {@link ToolProvider} SPI the agent already uses
 * for its own {@code @Tool} methods and for MCP servers, no change to the executor is needed — attach
 * it to a supervisor with {@link Builder#attachTo(AgentExecutor)} (or build it and pass it to
 * {@link AgentExecutor#addToolProvider}). Each delegation runs as a fresh, independent sub-conversation,
 * so specialists stay stateless across calls and safe to reuse — including from supervisor runs that an
 * {@link io.github.ivannavas.sprout.orchestration.orchestrator.AgentOrchestrator} drives concurrently.
 */
public final class AgentDelegation implements ToolProvider {

    /** A named specialist agent and the description the supervisor's model sees when choosing it. */
    public record Specialist(String name, String description, AgentExecutor agent) {}

    // JSON-Schema advertised for every specialist tool: a single free-text task to hand off.
    private static final String TASK_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\","
            + "\"description\":\"The task or question to hand to this specialist.\"}},\"required\":[\"task\"]}";

    private final ObjectMapper json = new ObjectMapper();
    private final List<Specialist> specialists;
    private final Map<String, Specialist> byName;

    private AgentDelegation(List<Specialist> specialists) {
        this.specialists = List.copyOf(specialists);
        Map<String, Specialist> index = new LinkedHashMap<>();
        for (Specialist specialist : specialists) {
            index.put(specialist.name(), specialist);
        }
        this.byName = index;
    }

    /** Starts building a delegation. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<ToolDefinition> tools() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Specialist specialist : specialists) {
            definitions.add(new ToolDefinition(specialist.name(), specialist.description(), TASK_SCHEMA));
        }
        return definitions;
    }

    @Override
    public ToolResult call(ToolCall call) {
        Specialist specialist = byName.get(call.name());
        if (specialist == null) {
            return ToolResult.failure(call.id(), "Unknown specialist: " + call.name());
        }
        try {
            String task = extractTask(call.argumentsJson());
            AgentResult result = specialist.agent().execute(specialist.name() + "-" + UUID.randomUUID(), task);
            return ToolResult.ok(call.id(), result.response());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolResult.failure(call.id(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private String extractTask(String argumentsJson) throws Exception {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        JsonNode task = json.readTree(argumentsJson).get("task");
        return task != null ? task.asText() : argumentsJson;
    }

    /** Builds an {@link AgentDelegation} from a set of named specialists. */
    public static final class Builder {

        private final List<Specialist> specialists = new ArrayList<>();

        /**
         * Registers a specialist under {@code name}; {@code description} is what the supervisor's model
         * reads when deciding whether to hand a subtask to it, so make it specific.
         */
        public Builder specialist(String name, String description, AgentExecutor agent) {
            specialists.add(new Specialist(name, description, agent));
            return this;
        }

        /** Builds the provider. */
        public AgentDelegation build() {
            if (specialists.isEmpty()) {
                throw new IllegalStateException("A delegation needs at least one specialist.");
            }
            return new AgentDelegation(specialists);
        }

        /** Builds the provider and attaches it to {@code supervisor} as a source of tools. */
        public AgentDelegation attachTo(AgentExecutor supervisor) {
            AgentDelegation delegation = build();
            supervisor.addToolProvider(delegation);
            return delegation;
        }
    }
}
