package io.github.ivannavas.sprout.abstrct;

import io.github.ivannavas.sprout.model.Message;

import java.util.List;

/**
 * Persists agent conversation history, keyed by conversation id. Implement it (and annotate with
 * {@link io.github.ivannavas.sprout.annotation.ConversationStore @ConversationStore}) to back agents
 * with a custom store; {@link io.github.ivannavas.sprout.impl.InMemoryConversationStore} is the default.
 */
public interface AbstractConversationStore {

    /** Returns the stored messages for a conversation, or an empty list if none exist. */
    List<Message> load(String conversationId);

    /** Appends messages to a conversation's history. */
    void append(String conversationId, List<Message> messages);

    /** Discards all stored history for a conversation. */
    void clear(String conversationId);
}
