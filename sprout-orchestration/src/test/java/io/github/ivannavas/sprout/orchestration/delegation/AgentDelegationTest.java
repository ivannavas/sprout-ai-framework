package io.github.ivannavas.sprout.orchestration.delegation;

import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.model.AgentData;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.Role;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import io.github.ivannavas.sprout.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDelegationTest {

    /** A specialist that simply echoes its task back with a prefix, so delegation is observable. */
    private static AgentExecutor echoAgent(String prefix) {
        return new AgentExecutor() {
            @Override
            public AgentResult execute(String conversationId, String prompt) {
                return new AgentResult(conversationId, prefix + prompt, 1, TokenUsage.ZERO);
            }
        };
    }

    @Test
    void exposesOneToolPerSpecialist() {
        AgentDelegation delegation = AgentDelegation.builder()
                .specialist("math", "Solves arithmetic", echoAgent("math:"))
                .specialist("history", "Answers history questions", echoAgent("history:"))
                .build();

        List<ToolDefinition> tools = delegation.tools();
        assertEquals(List.of("math", "history"), tools.stream().map(ToolDefinition::name).toList());
        assertEquals("Solves arithmetic", tools.get(0).description());
    }

    @Test
    void callRoutesToTheNamedSpecialist() {
        AgentDelegation delegation = AgentDelegation.builder()
                .specialist("math", "Solves arithmetic", echoAgent("math:"))
                .build();

        ToolResult result = delegation.call(new ToolCall("c1", "math", "{\"task\":\"2+2\"}"));

        assertFalse(result.error());
        assertEquals("math:2+2", result.content());
    }

    @Test
    void callOnUnknownSpecialistFails() {
        AgentDelegation delegation = AgentDelegation.builder()
                .specialist("math", "Solves arithmetic", echoAgent("math:"))
                .build();

        ToolResult result = delegation.call(new ToolCall("c1", "writer", "{\"task\":\"x\"}"));

        assertTrue(result.error());
        assertTrue(result.content().contains("writer"));
    }

    @Test
    void supervisorDelegatesAndComposesFinalAnswer() {
        AgentExecutor supervisor = new AgentExecutor();
        supervisor.configure(new AgentData(new RoutingModel(), new InMemoryConversationStore(), null, "", 4, Map.of()));

        AgentDelegation.builder()
                .specialist("math", "Solves arithmetic", echoAgent("solved: "))
                .attachTo(supervisor);

        AgentResult result = supervisor.execute("session", "What is 2+2?");

        assertEquals("Delegated result -> solved: What is 2+2?", result.response());
        assertEquals(2, result.iterations()); // one turn to delegate, one to compose
    }

    // A supervisor model: it delegates the user's question to the first available specialist tool,
    // then — once that specialist has answered (a TOOL message) — composes the final reply.
    private static final class RoutingModel extends ModelExecutor {
        @Override
        public ModelResponse chat(ModelRequest request) {
            List<Message> messages = request.messages();
            Message last = messages.get(messages.size() - 1);

            if (last.role() == Role.TOOL) {
                return new ModelResponse(
                        Message.assistant("Delegated result -> " + last.toolResult().content()),
                        TokenUsage.ZERO, FinishReason.STOP);
            }

            ToolDefinition specialist = request.tools().get(0);
            ToolCall call = new ToolCall("call-1", specialist.name(), "{\"task\":\"" + userText(messages) + "\"}");
            return new ModelResponse(Message.assistant("", List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        }

        private String userText(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role() == Role.USER) {
                    return messages.get(i).content();
                }
            }
            return "";
        }
    }
}
