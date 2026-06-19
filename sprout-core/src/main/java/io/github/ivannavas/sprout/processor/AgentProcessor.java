package io.github.ivannavas.sprout.processor;

import io.github.ivannavas.sprout.abstrct.AbstractConversationStore;
import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.AgentData;

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

        AbstractConversationStore store = resolveConversationStore(agent.conversationStore());
        ((AgentExecutor) instance).configure(AgentData.fromAnnotation(agent, model, store, collectToolMethods()));
        sproutContainer.registerSingleton(executorBeanName(component), instance);

        return instance;
    }

    /**
     * Resolves the agent's conversation store. If the declared type is a managed bean (a Sprout
     * component or a bean from an embedding container such as Spring), that instance is used — so the
     * store can have its own dependencies injected (e.g. a JPA repository). Otherwise it is created
     * via its no-arg constructor, which is enough for the default in-memory store.
     */
    private AbstractConversationStore resolveConversationStore(Class<?> storeClass) {
        Object managed = sproutContainer.getOrCreateByType(storeClass);
        if (managed instanceof AbstractConversationStore store) {
            return store;
        }
        try {
            return (AbstractConversationStore) storeClass.getDeclaredConstructors()[0].newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sprout: failed creating conversation store " + storeClass, e);
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
