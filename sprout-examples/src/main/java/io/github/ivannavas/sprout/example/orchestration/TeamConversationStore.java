package io.github.ivannavas.sprout.example.orchestration;

import io.github.ivannavas.sprout.annotation.ConversationStore;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;

/**
 * A single in-memory store shared by the whole team. Hand-off needs the agents to share one store so
 * the receiving agent sees the transcript the previous one built; being a scanned {@code @ConversationStore}
 * component, this is a singleton, so every agent that selects it gets the same instance (the default
 * store, by contrast, is created per agent in a plain app). Delegation and orchestration also use it,
 * harmlessly, since their runs use distinct conversation ids.
 */
@ConversationStore
public class TeamConversationStore extends InMemoryConversationStore {
}
