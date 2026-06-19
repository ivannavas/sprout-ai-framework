package io.github.ivannavas.sprout.executor;

import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.model.AgentData;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.StreamListener;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutorStreamTest {

    /** An agent whose own {@code @Tool} method is invoked during the streamed run. */
    static class EchoAgent extends AgentExecutor {
        @Tool(description = "Echoes the text back")
        public String echo(String text) {
            return text;
        }
    }

    /** Asks for the {@code echo} tool on the first turn, then streams a final answer. */
    static class ToolThenAnswerModel extends ModelExecutor {
        @Override
        public ModelResponse chat(ModelRequest request) {
            boolean toolDone = request.messages().stream().anyMatch(m -> m.toolResult() != null);
            if (!toolDone) {
                ToolCall call = new ToolCall("c1", "echo", "{\"text\":\"hi\"}");
                return new ModelResponse(Message.assistant(null, List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
            }
            String echoed = request.messages().stream()
                    .filter(m -> m.toolResult() != null)
                    .reduce((first, second) -> second).orElseThrow()
                    .toolResult().content();
            return new ModelResponse(Message.assistant("answer: " + echoed), TokenUsage.ZERO, FinishReason.STOP);
        }
    }

    /** Never stops asking for the tool, so the loop runs out of iterations. */
    static class AlwaysToolModel extends ModelExecutor {
        @Override
        public ModelResponse chat(ModelRequest request) {
            ToolCall call = new ToolCall("c1", "echo", "{\"text\":\"hi\"}");
            return new ModelResponse(Message.assistant(null, List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
        }
    }

    private static EchoAgent agent(ModelExecutor model) throws NoSuchMethodException {
        Method echo = EchoAgent.class.getMethod("echo", String.class);
        echo.setAccessible(true);
        EchoAgent agent = new EchoAgent();
        agent.configure(new AgentData(model, new InMemoryConversationStore(), "", 3, Map.of("echo", echo)));
        return agent;
    }

    @Test
    void streamsTokensToolCallsAndFinalResponse() throws Exception {
        EchoAgent agent = agent(new ToolThenAnswerModel());

        List<String> tokens = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        AtomicReference<ModelResponse> completed = new AtomicReference<>();

        agent.executeStream("session", "echo hi", new StreamListener() {
            @Override
            public void onToken(String token) {
                tokens.add(token);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                toolCalls.add(toolCall);
            }

            @Override
            public void onComplete(ModelResponse response) {
                completed.set(response);
            }
        });

        assertEquals(1, toolCalls.size(), "the echo tool call should be streamed");
        assertEquals("echo", toolCalls.get(0).name());
        assertEquals(1, tokens.size(), "the final answer should arrive as a token");
        assertTrue(tokens.get(0).contains("hi"));
        assertNotNull(completed.get(), "onComplete should fire once with the final response");
        assertEquals(tokens.get(0), completed.get().message().content());
    }

    @Test
    void reportsFailureThroughOnError() throws Exception {
        EchoAgent agent = agent(new AlwaysToolModel());

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<ModelResponse> completed = new AtomicReference<>();

        agent.executeStream("session", "loop forever", new StreamListener() {
            @Override
            public void onComplete(ModelResponse response) {
                completed.set(response);
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
            }
        });

        assertNotNull(error.get(), "exceeding maxIterations should be reported to onError");
        assertEquals(null, completed.get(), "onComplete must not fire when the run fails");
    }
}
