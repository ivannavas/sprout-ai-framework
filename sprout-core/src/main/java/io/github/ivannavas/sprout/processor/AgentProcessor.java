package io.github.ivannavas.sprout.processor;

import io.github.ivannavas.sprout.abstrct.AbstractConversationStore;
import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.abstrct.AbstractVectorStore;
import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.embedding.EmbeddingModel;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.AgentData;
import io.github.ivannavas.sprout.rag.Retriever;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Processor for {@link Agent @Agent} components. The agent class extends {@link AgentExecutor}, so the
 * instance is its own executor: beyond the standard wiring this resolves the {@code @Model}, collects
 * the {@code @Tool} methods and {@link AgentExecutor#configure configures} the instance from the
 * annotation, registering it under {@link #executorBeanName(Class)}. A reference example of extending
 * {@link ComponentProcessor} via {@link Processor @Processor}.
 */
@Processor(Agent.class)
public class AgentProcessor extends ComponentProcessor {

    public AgentProcessor(Class<?> component, SproutContainer sproutContainer) {
        super(component, sproutContainer);
    }

    @Override
    public void validate() {
        super.validate();
        if (!AgentExecutor.class.isAssignableFrom(component)) {
            throw new IllegalArgumentException("@Agent " + component + " must extend AgentExecutor");
        }
    }

    @Override
    public Object instantiate() {
        Object instance = super.instantiate();
        Agent agent = component.getAnnotation(Agent.class);

        Object resolved = sproutContainer.getOrCreateByType(agent.model());
        if (!(resolved instanceof ModelExecutor model)) {
            throw new IllegalStateException("@Agent " + component + " references model " + agent.model()
                    + " which is not a managed ModelExecutor");
        }

        AbstractConversationStore store = resolveCollaborator(agent.conversationStore(),
                AbstractConversationStore.class, "conversation store");
        AgentExecutor executor = (AgentExecutor) instance;
        executor.configure(
                AgentData.fromAnnotation(agent, model, store, resolveRetriever(agent), collectToolMethods()));
        executor.setEventBus((AbstractEventBus) sproutContainer.getOrCreateByType(AbstractEventBus.class));
        sproutContainer.registerSingleton(executorBeanName(component), instance);

        return instance;
    }

    /**
     * Builds the agent's RAG retriever, or returns {@code null} when no vector store is declared (the
     * {@link AbstractVectorStore} sentinel default). A declared store requires an embedding model, so its
     * absence is a configuration error rather than a silent fallback.
     */
    private Retriever resolveRetriever(Agent agent) {
        if (agent.vectorStore() == AbstractVectorStore.class) {
            return null;
        }
        if (agent.embeddingModel() == EmbeddingModel.class) {
            throw new IllegalStateException("@Agent " + component + " declares vectorStore " + agent.vectorStore()
                    + " but no embeddingModel; set @Agent(embeddingModel = ...)");
        }
        AbstractVectorStore store = resolveCollaborator(agent.vectorStore(), AbstractVectorStore.class, "vector store");
        EmbeddingModel embedding = resolveCollaborator(agent.embeddingModel(), EmbeddingModel.class, "embedding model");
        return new Retriever(embedding, store, agent.retrievalTopK());
    }

    /**
     * Resolves a collaborator the agent references by type. If the declared type is a managed bean (a
     * Sprout component or a bean from an embedding container), that instance is used — so
     * the collaborator can have its own dependencies injected (e.g. a JPA repository, or a vector store
     * shared with indexing code). Otherwise it is created via its no-arg constructor, which is enough for
     * the in-memory defaults.
     */
    private <T> T resolveCollaborator(Class<?> declared, Class<T> type, String what) {
        Object managed = sproutContainer.getOrCreateByType(declared);
        if (type.isInstance(managed)) {
            return type.cast(managed);
        }
        try {
            return type.cast(declared.getDeclaredConstructors()[0].newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sprout: failed creating " + what + " " + declared, e);
        }
    }

    private Map<String, Method> collectToolMethods() {
        Map<String, Method> tools = new HashMap<>();
        for (Method method : component.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            method.setAccessible(true);
            String name = tool.name().isEmpty() ? method.getName() : tool.name();
            tools.put(name, method);
        }
        return tools;
    }

    // Conventional bean name for an @Agent's executor: camelCase class name + "Executor". Exposed so
    // cooperating processors (e.g. the MCP client wiring) can locate the executor for an agent class.
    public static String executorBeanName(Class<?> component) {
        String simple = component.getSimpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1) + "Executor";
    }
}
