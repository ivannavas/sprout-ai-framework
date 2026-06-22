package io.github.ivannavas.sprout.orchestration.handoff;

import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import io.github.ivannavas.sprout.model.ToolResult;
import io.github.ivannavas.sprout.tool.ToolProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates a team of agents that can <em>hand off</em> the conversation to one another. Unlike
 * {@link io.github.ivannavas.sprout.orchestration.delegation.AgentDelegation delegation} — where a
 * supervisor calls a specialist as an isolated sub-task and then composes the reply itself — a hand-off
 * <em>transfers control</em>: the receiving agent continues the same, shared conversation and produces
 * the final answer (and may hand off again).
 *
 * <p>Each member is given a {@code handoff_to_<member>} tool for every other member (through the same
 * {@link ToolProvider} SPI used for {@code @Tool} methods and MCP). When the active agent's model calls
 * one, this coordinator switches the active agent and re-runs it on the same conversation id, so the new
 * agent sees the whole transcript while applying its own system prompt. The loop ends when an agent
 * finishes its turn without handing off; its answer is the result. {@link Builder#maxHandoffs(int)}
 * bounds the number of transfers.
 *
 * <p>The receiving agent picks the transcript up through its conversation store, so the team members
 * must share one store — point them at a common {@code @ConversationStore} bean (note the default
 * in-memory store is created per agent in a plain app, so it is <em>not</em> shared). A single
 * {@code AgentHandoff} instance drives one conversation at a time; build separate teams to run hand-offs
 * concurrently.
 */
public final class AgentHandoff {

    /** A member of a hand-off team: its {@code name}, a {@code description} other agents read when
     * deciding to transfer to it, and the agent itself. */
    public record Member(String name, String description, AgentExecutor agent) {}

    /** The outcome of a hand-off run: the final {@code response}, which agent produced it, and the
     * ordered {@code path} of agents the conversation passed through. */
    public record HandoffResult(String response, String finalAgent, List<String> path) {}

    private static final int DEFAULT_MAX_HANDOFFS = 8;

    private final Map<String, Member> members;
    private final String entryAgent;
    private final int maxHandoffs;
    private final AtomicReference<String> pendingTarget;

    private AgentHandoff(Map<String, Member> members, String entryAgent, int maxHandoffs,
                         AtomicReference<String> pendingTarget) {
        this.members = members;
        this.entryAgent = entryAgent;
        this.maxHandoffs = maxHandoffs;
        this.pendingTarget = pendingTarget;
    }

    /** Starts building a hand-off team. */
    public static Builder builder() {
        return new Builder();
    }

    /** Runs the conversation starting from the team's entry agent (the first one registered). */
    public HandoffResult run(String prompt) {
        return run(entryAgent, prompt);
    }

    /** Runs the conversation starting from {@code startAgent}. */
    public HandoffResult run(String startAgent, String prompt) {
        if (!members.containsKey(startAgent)) {
            throw new IllegalArgumentException("Unknown agent: " + startAgent);
        }
        String conversationId = "handoff-" + UUID.randomUUID();
        List<String> path = new ArrayList<>();
        String activeName = startAgent;
        String input = prompt;

        for (int hop = 0; hop <= maxHandoffs; hop++) {
            path.add(activeName);
            pendingTarget.set(null);

            AgentResult result = members.get(activeName).agent().execute(conversationId, input);

            String next = pendingTarget.get();
            if (next == null) {
                return new HandoffResult(result.response(), activeName, List.copyOf(path));
            }

            // The receiving agent inherits the shared transcript and applies its own system prompt, so
            // this is just the turn that wakes it up to continue.
            input = "Please review the conversation so far and continue helping the user.";
            activeName = next;
        }
        throw new IllegalStateException(
                "Hand-off exceeded " + maxHandoffs + " transfers without a final answer.");
    }

    // One member's view of the hand-off tools: a handoff_to_<name> tool for every other member, all
    // recording the chosen target into the coordinator's shared holder.
    private static final class HandoffTool implements ToolProvider {

        private static final String SCHEMA =
                "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\","
                + "\"description\":\"Why you are handing off.\"}},\"required\":[]}";

        private final List<ToolDefinition> tools;
        private final Map<String, String> targetByTool;
        private final AtomicReference<String> pendingTarget;

        HandoffTool(String owner, Collection<Member> all, AtomicReference<String> pendingTarget) {
            this.pendingTarget = pendingTarget;
            List<ToolDefinition> definitions = new ArrayList<>();
            Map<String, String> index = new LinkedHashMap<>();
            for (Member member : all) {
                if (member.name().equals(owner)) {
                    continue;
                }
                String toolName = "handoff_to_" + member.name();
                definitions.add(new ToolDefinition(toolName,
                        "Hand off the conversation to " + member.name() + ". " + member.description(), SCHEMA));
                index.put(toolName, member.name());
            }
            this.tools = List.copyOf(definitions);
            this.targetByTool = index;
        }

        @Override
        public List<ToolDefinition> tools() {
            return tools;
        }

        @Override
        public ToolResult call(ToolCall call) {
            String target = targetByTool.get(call.name());
            if (target == null) {
                return ToolResult.failure(call.id(), "Unknown hand-off: " + call.name());
            }
            pendingTarget.set(target);
            return ToolResult.ok(call.id(), "Control will pass to " + target + ". Acknowledge the transfer briefly.");
        }
    }

    /** Builds an {@link AgentHandoff} from a team of named agents. */
    public static final class Builder {

        private final Map<String, Member> members = new LinkedHashMap<>();
        private String entryAgent;
        private int maxHandoffs = DEFAULT_MAX_HANDOFFS;

        /**
         * Adds a team member under {@code name}; {@code description} is what other agents read when
         * deciding whether to hand off to it. The first member added is the entry agent unless
         * {@link #entryAgent(String)} says otherwise.
         */
        public Builder member(String name, String description, AgentExecutor agent) {
            members.put(name, new Member(name, description, agent));
            if (entryAgent == null) {
                entryAgent = name;
            }
            return this;
        }

        /** Sets which member starts the conversation (defaults to the first one added). */
        public Builder entryAgent(String name) {
            this.entryAgent = name;
            return this;
        }

        /** Caps how many transfers a single run may make before failing (default 8). */
        public Builder maxHandoffs(int maxHandoffs) {
            this.maxHandoffs = Math.max(1, maxHandoffs);
            return this;
        }

        /** Wires the hand-off tools onto every member and returns the coordinator. */
        public AgentHandoff build() {
            if (members.size() < 2) {
                throw new IllegalStateException("A hand-off team needs at least two agents.");
            }
            AtomicReference<String> pendingTarget = new AtomicReference<>();
            for (Member member : members.values()) {
                member.agent().addToolProvider(new HandoffTool(member.name(), members.values(), pendingTarget));
            }
            return new AgentHandoff(new LinkedHashMap<>(members), entryAgent, maxHandoffs, pendingTarget);
        }
    }
}
