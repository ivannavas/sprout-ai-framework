package io.github.ivannavas.sprout.agentinheritance;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Own package so component scanning only picks up the beans declared here.
// An agent may split its tools across a class hierarchy (e.g. a shared base of read-only lookups);
// the processor must collect the inherited @Tool methods, not just the agent's own.
class InheritedToolsTest {

    /** Base of the hierarchy: contributes a tool the agent never declares itself. */
    public static class ReaderBase extends AgentExecutor {
        @Tool(name = "readTool", description = "Inherited from the grandparent")
        public String readTool() {
            return "read";
        }

        @Tool(name = "overridableTool", description = "Declared on the base")
        public String overridableTool() {
            return "from base";
        }
    }

    /** Middle of the hierarchy: adds another tool. */
    public static class WriterBase extends ReaderBase {
        @Tool(name = "writeTool", description = "Inherited from the parent")
        public String writeTool() {
            return "write";
        }
    }

    @Agent(model = ToolCallingModel.class, systemPrompt = "test")
    public static class InheritingAgent extends WriterBase {
        @Tool(name = "ownTool", description = "Declared on the agent itself")
        public String ownTool() {
            return "own";
        }

        /** Re-declares an inherited tool name: the most-derived declaration must win. */
        @Override
        @Tool(name = "overridableTool", description = "Overridden on the agent")
        public String overridableTool() {
            return "from agent";
        }
    }

    /** Calls the tool named by the prompt, then answers with its result, recording what it was offered. */
    @Model
    public static class ToolCallingModel extends ModelExecutor {
        static volatile List<ToolDefinition> lastOfferedTools = List.of();

        @Override
        public ModelResponse chat(ModelRequest request) {
            lastOfferedTools = request.tools();
            boolean toolDone = request.messages().stream().anyMatch(m -> m.toolResult() != null);
            if (!toolDone) {
                String wanted = request.messages().getLast().content();
                ToolCall call = new ToolCall("c1", wanted, "{}");
                return new ModelResponse(Message.assistant(null, List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
            }
            String result = request.messages().stream()
                    .filter(m -> m.toolResult() != null)
                    .reduce((first, second) -> second).orElseThrow()
                    .toolResult().content();
            return new ModelResponse(Message.assistant(result), TokenUsage.ZERO, FinishReason.STOP);
        }
    }

    private static AgentExecutor agent() {
        SproutContainer container = SproutApplication.run(InheritedToolsTest.class);
        return container.getSingleton(InheritingAgent.class);
    }

    @Test
    void agentAdvertisesInheritedAndOwnTools() {
        run(agent(), "ownTool");

        List<String> offered = ToolCallingModel.lastOfferedTools.stream().map(ToolDefinition::name).sorted().toList();

        assertEquals(List.of("overridableTool", "ownTool", "readTool", "writeTool"), offered,
                "tools declared anywhere up the hierarchy should be advertised to the model");
    }

    @Test
    void inheritedToolsAreDispatchedToTheAgentInstance() {
        AgentExecutor agent = agent();

        // Each run asks the model to call one tool by name; the answer echoes the tool's return value.
        assertEquals("read", run(agent, "readTool"), "a tool from the grandparent should be callable");
        assertEquals("write", run(agent, "writeTool"), "a tool from the parent should be callable");
        assertEquals("own", run(agent, "ownTool"), "the agent's own tool should still be callable");
    }

    @Test
    void mostDerivedDeclarationOfAToolNameWins() {
        assertEquals("from agent", run(agent(), "overridableTool"),
                "an agent re-declaring an inherited tool name should override the base's");
    }

    @Test
    void inheritedToolKeepsTheDescriptionWhereItWasDeclared() {
        run(agent(), "ownTool");

        ToolDefinition read = ToolCallingModel.lastOfferedTools.stream()
                .filter(t -> t.name().equals("readTool"))
                .findFirst().orElseThrow();

        assertTrue(read.description().contains("grandparent"),
                "an inherited tool should keep the description from where it was declared");
    }

    private static String run(AgentExecutor agent, String toolName) {
        AgentResult result = agent.execute("session-" + toolName, toolName);
        // The model echoes the tool result back as the final answer, so it arrives JSON-encoded.
        return result.response().replace("\"", "");
    }
}
