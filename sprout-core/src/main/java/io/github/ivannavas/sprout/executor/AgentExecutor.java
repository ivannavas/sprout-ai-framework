package io.github.ivannavas.sprout.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.model.*;
import io.github.ivannavas.sprout.rag.Retriever;
import io.github.ivannavas.sprout.tool.ToolProvider;
import io.github.ivannavas.sprout.tool.ToolReflection;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class for agents, and the engine that drives their reasoning loop: it sends the conversation
 * to the model, dispatches any tool calls the model requests (to the agent's own {@code @Tool}
 * methods or to attached {@link ToolProvider}s), feeds the results back, and repeats until the model
 * returns a final answer or {@code maxIterations} is reached. Conversation history is persisted
 * through the agent's store.
 *
 * <p>An {@link io.github.ivannavas.sprout.annotation.Agent @Agent} class <em>extends</em> this class
 * — just as a {@code @Model} class extends {@code ModelExecutor} — and declares its tools as
 * {@code @Tool} methods on itself. The container instantiates it, {@link #configure(AgentData)
 * configures} it from the annotation, and registers it (also under the alias
 * {@code <agentName>Executor}). Call {@link #execute(String, String)} for a blocking run or
 * {@link #executeStream(String, String, StreamListener)} to stream progress; override either to
 * customise the loop.
 */
public class AgentExecutor {

    private static final ObjectMapper json = new ObjectMapper();

    private AgentData agentData;
    private final List<ToolProvider> toolProviders = new ArrayList<>();
    private Map<String, ToolProvider> providerByTool;

    /**
     * Supplies the configuration derived from the {@code @Agent} annotation. Called once by the
     * container right after construction; not intended to be invoked by application code.
     */
    public void configure(AgentData agentData) {
        this.agentData = agentData;
    }

    /**
     * Adds an external source of tools (e.g. an MCP server) whose tools become callable by the model
     * alongside the agent's own {@code @Tool} methods. Intended to be wired during container startup,
     * before the agent first runs.
     */
    public void addToolProvider(ToolProvider toolProvider) {
        toolProviders.add(toolProvider);
        providerByTool = null;
    }

    /** Runs the agent to completion and returns the final answer. */
    public AgentResult execute(String conversationId, String prompt) {
        return run(conversationId, prompt, null);
    }

    /**
     * Runs the agent while streaming progress to {@code listener}: assistant tokens via
     * {@code onToken}, each requested tool via {@code onToolCall}, and the final assistant response
     * via {@code onComplete} (failures, including exceeding {@code maxIterations}, go to
     * {@code onError}). Token granularity depends on the model: providers that override
     * {@code chatStream} emit incremental chunks, otherwise each turn's text arrives in one piece.
     */
    public void executeStream(String conversationId, String prompt, StreamListener listener) {
        try {
            run(conversationId, prompt, listener);
        } catch (RuntimeException e) {
            listener.onError(e);
        }
    }

    private AgentResult run(String conversationId, String prompt, StreamListener listener) {
        List<Message> history = new ArrayList<>();

        // Apply this agent's own system prompt at the head of every run rather than persisting it once.
        // The stored transcript therefore holds no system message, so when an agent picks up a
        // conversation another agent started (a hand-off), it still governs its turns with its own
        // instructions instead of inheriting the previous agent's — and any persisted system message
        // from an earlier turn is dropped here so only the current prompt is in effect.
        if (!agentData.systemPrompt().isEmpty()) {
            history.add(Message.system(agentData.systemPrompt()));
        }
        for (Message message : agentData.conversationStore().load(conversationId)) {
            if (message.role() != Role.SYSTEM) {
                history.add(message);
            }
        }

        // Tracked separately from the system prompt and prior history, so only genuinely new turns are
        // appended to the store.
        List<Message> newMessages = new ArrayList<>();
        Message userMessage = Message.user(prompt);
        newMessages.add(userMessage);

        // RAG: the model sees the prompt augmented with retrieved context, but the store keeps the
        // original question — retrieval is redone per turn (like the system prompt, it is applied per run,
        // not persisted), so a reloaded history never carries stale context forward.
        history.add(augmentWithRetrieval(userMessage));

        List<ToolDefinition> tools = buildToolDefinitions();
        TokenUsage totalUsage = TokenUsage.ZERO;

        for (int iteration = 1; iteration <= agentData.maxIterations(); iteration++) {
            ModelResponse response = callModel(new ModelRequest(List.copyOf(history), tools), listener);

            totalUsage = totalUsage.plus(response.usage());
            history.add(response.message());
            newMessages.add(response.message());

            if (!response.message().requestsTools()) {
                agentData.conversationStore().append(conversationId, newMessages);
                if (listener != null) {
                    listener.onComplete(response);
                }
                return new AgentResult(conversationId, response.message().content(), iteration, totalUsage);
            }

            for (ToolCall call : response.message().toolCalls()) {
                Message toolMessage = Message.tool(dispatch(call));
                history.add(toolMessage);
                newMessages.add(toolMessage);
            }
        }

        agentData.conversationStore().append(conversationId, newMessages);
        throw new IllegalStateException(
                "Agent exceeded " + agentData.maxIterations() + " iterations without a final answer");
    }

    /**
     * Returns the message the model should see for {@code userMessage}: the original when RAG is off, or
     * one whose text is prefixed with the documents the retriever finds for the prompt. When retrieval
     * comes back empty the original is used unchanged.
     */
    private Message augmentWithRetrieval(Message userMessage) {
        Retriever retriever = agentData.retriever();
        if (retriever == null) {
            return userMessage;
        }
        List<SearchResult> retrieved = retriever.retrieve(userMessage.content());
        if (retrieved.isEmpty()) {
            return userMessage;
        }
        StringBuilder augmented = new StringBuilder(
                "Use the following retrieved context to answer the question. "
                        + "If it is not relevant, rely on your own knowledge.\n\nContext:\n");
        int index = 1;
        for (SearchResult result : retrieved) {
            augmented.append('[').append(index++).append("] ").append(result.document().text()).append("\n\n");
        }
        augmented.append("Question: ").append(userMessage.content());
        return Message.user(augmented.toString());
    }

    /**
     * One model call. Without a listener this is a plain blocking {@code chat}; with one it goes
     * through {@code chatStream}, forwarding tokens and tool calls as they arrive while capturing the
     * final response to drive the loop.
     */
    private ModelResponse callModel(ModelRequest request, StreamListener listener) {
        if (listener == null) {
            return agentData.model().chat(request);
        }
        AtomicReference<ModelResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        agentData.model().chatStream(request, new StreamListener() {
            @Override
            public void onToken(String token) {
                listener.onToken(token);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                listener.onToolCall(toolCall);
            }

            @Override
            public void onComplete(ModelResponse modelResponse) {
                response.set(modelResponse);
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw new RuntimeException("Model streaming failed", failure.get());
        }
        return response.get();
    }

    private List<ToolDefinition> buildToolDefinitions() {
        List<ToolDefinition> tools = new ArrayList<>();
        for (Map.Entry<String, Method> entry : agentData.toolMethods().entrySet()) {
            Method method = entry.getValue();
            Tool annotation = method.getAnnotation(Tool.class);
            String name = annotation != null && !annotation.name().isEmpty() ? annotation.name() : entry.getKey();
            String description = annotation != null ? annotation.description() : "";
            tools.add(new ToolDefinition(name, description, ToolReflection.schemaFor(method)));
        }
        for (ToolProvider provider : toolProviders) {
            tools.addAll(provider.tools());
        }
        return tools;
    }

    private ToolResult dispatch(ToolCall call) {
        Method method = agentData.toolMethods().get(call.name());
        if (method != null) {
            try {
                Object result = ToolReflection.invoke(this, method, call.argumentsJson());
                return ToolResult.ok(call.id(), result == null ? "done" : json.writeValueAsString(result));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return ToolResult.failure(call.id(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }

        ToolProvider provider = providerByTool().get(call.name());
        if (provider != null) {
            return provider.call(call);
        }
        return ToolResult.failure(call.id(), "Unknown tool: " + call.name());
    }

    // Indexes which provider owns each tool name (built lazily and cached, since providers are added
    // during startup). Local @Tool methods always take precedence and are not indexed here.
    private Map<String, ToolProvider> providerByTool() {
        if (providerByTool == null) {
            Map<String, ToolProvider> index = new HashMap<>();
            for (ToolProvider provider : toolProviders) {
                for (ToolDefinition definition : provider.tools()) {
                    index.putIfAbsent(definition.name(), provider);
                }
            }
            providerByTool = index;
        }
        return providerByTool;
    }
}
