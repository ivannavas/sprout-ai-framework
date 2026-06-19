package io.github.ivannavas.sprout.model;

import io.github.ivannavas.sprout.abstrct.AbstractConversationStore;
import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.ModelExecutor;

import java.lang.reflect.Method;
import java.util.Map;

/** The runtime configuration of an agent, derived from its {@link Agent @Agent} annotation. */
public record AgentData(
        ModelExecutor model,
        AbstractConversationStore conversationStore,
        String systemPrompt,
        int maxIterations,
        Map<String, Method> toolMethods
) {
    public static AgentData fromAnnotation(Agent annotation, ModelExecutor model,
                                           AbstractConversationStore conversationStore,
                                           Map<String, Method> toolMethods) {
        return new AgentData(model, conversationStore, annotation.systemPrompt(),
                annotation.maxIterations(), toolMethods);
    }
}
