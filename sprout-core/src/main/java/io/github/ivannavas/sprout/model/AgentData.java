package io.github.ivannavas.sprout.model;

import io.github.ivannavas.sprout.abstrct.AbstractConversationStore;
import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.rag.Retriever;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * The runtime configuration of an agent, derived from its {@link Agent @Agent} annotation. The
 * {@code retriever} is {@code null} unless the agent enabled RAG by declaring a vector store.
 */
public record AgentData(
        ModelExecutor model,
        AbstractConversationStore conversationStore,
        Retriever retriever,
        String systemPrompt,
        int maxIterations,
        Map<String, Method> toolMethods
) {
    public static AgentData fromAnnotation(Agent annotation, ModelExecutor model,
                                           AbstractConversationStore conversationStore,
                                           Retriever retriever, Map<String, Method> toolMethods) {
        return new AgentData(model, conversationStore, retriever, annotation.systemPrompt(),
                annotation.maxIterations(), toolMethods);
    }
}
