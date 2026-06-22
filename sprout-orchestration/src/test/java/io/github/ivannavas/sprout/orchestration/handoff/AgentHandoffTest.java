package io.github.ivannavas.sprout.orchestration.handoff;

import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.abstrct.AbstractConversationStore;
import io.github.ivannavas.sprout.model.AgentData;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.Role;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentHandoffTest {

    // A team shares one conversation store, exactly as agents using the default store do at runtime.
    private final AbstractConversationStore store = new InMemoryConversationStore();

    private AgentExecutor agentWith(ModelExecutor model) {
        return agentWith(model, "system");
    }

    private AgentExecutor agentWith(ModelExecutor model, String systemPrompt) {
        AgentExecutor agent = new AgentExecutor();
        agent.configure(new AgentData(model, store, systemPrompt, 4, Map.of()));
        return agent;
    }

    @Test
    void entryAgentAnswersWithoutHandingOff() {
        AgentHandoff team = AgentHandoff.builder()
                .member("triage", "Routes the user", agentWith(new AnswerModel("handled by triage")))
                .member("billing", "Billing questions", agentWith(new AnswerModel("billing answer")))
                .build();

        AgentHandoff.HandoffResult result = team.run("hello");

        assertEquals("handled by triage", result.response());
        assertEquals("triage", result.finalAgent());
        assertEquals(List.of("triage"), result.path());
    }

    @Test
    void controlTransfersAndTargetProducesTheFinalAnswer() {
        AgentHandoff team = AgentHandoff.builder()
                .member("triage", "Routes the user", agentWith(new HandoffModel("billing")))
                .member("billing", "Billing questions", agentWith(new AnswerModel("your invoice is 42")))
                .build();

        AgentHandoff.HandoffResult result = team.run("I have a billing question");

        assertEquals("your invoice is 42", result.response());
        assertEquals("billing", result.finalAgent());
        assertEquals(List.of("triage", "billing"), result.path());
    }

    @Test
    void maxHandoffsStopsAPingPongLoop() {
        // Two agents that always hand off to each other would loop forever without the cap.
        AgentHandoff team = AgentHandoff.builder()
                .member("a", "Agent A", agentWith(new HandoffModel("b")))
                .member("b", "Agent B", agentWith(new HandoffModel("a")))
                .maxHandoffs(3)
                .build();

        assertThrows(IllegalStateException.class, () -> team.run("loop forever"));
    }

    @Test
    void receivingAgentAppliesItsOwnSystemPrompt() {
        CapturingModel billingModel = new CapturingModel("billing answer");
        AgentHandoff team = AgentHandoff.builder()
                .member("triage", "Routes", agentWith(new HandoffModel("billing"), "TRIAGE PROMPT"))
                .member("billing", "Billing", agentWith(billingModel, "BILLING PROMPT"))
                .build();

        team.run("question");

        // Even though triage started the shared conversation, billing must run under its own prompt.
        assertEquals("BILLING PROMPT", billingModel.systemPromptSeen);
    }

    @Test
    void buildRequiresAtLeastTwoAgents() {
        AgentHandoff.Builder builder = AgentHandoff.builder()
                .member("solo", "Only one", agentWith(new AnswerModel("x")));
        assertThrows(IllegalStateException.class, builder::build);
    }

    // Always answers directly, never hands off.
    private static final class AnswerModel extends ModelExecutor {
        private final String answer;

        AnswerModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            return new ModelResponse(Message.assistant(answer), TokenUsage.ZERO, FinishReason.STOP);
        }
    }

    // Records the system prompt it was given, then answers; used to assert per-agent system prompts.
    private static final class CapturingModel extends ModelExecutor {
        private final String answer;
        private volatile String systemPromptSeen;

        CapturingModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            request.messages().stream()
                    .filter(message -> message.role() == Role.SYSTEM)
                    .findFirst()
                    .ifPresent(message -> systemPromptSeen = message.content());
            return new ModelResponse(Message.assistant(answer), TokenUsage.ZERO, FinishReason.STOP);
        }
    }

    // Hands off to a fixed target on its first turn, then wraps up once the transfer is acknowledged.
    private static final class HandoffModel extends ModelExecutor {
        private final String target;

        HandoffModel(String target) {
            this.target = target;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            List<Message> messages = request.messages();
            Message last = messages.get(messages.size() - 1);

            if (last.role() == Role.TOOL) {
                return new ModelResponse(Message.assistant("Transferring you now."),
                        TokenUsage.ZERO, FinishReason.STOP);
            }
            ToolCall call = new ToolCall("call-1", "handoff_to_" + target, "{}");
            return new ModelResponse(Message.assistant("", List.of(call)),
                    TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        }
    }
}
