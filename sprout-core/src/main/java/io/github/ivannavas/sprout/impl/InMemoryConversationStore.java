package io.github.ivannavas.sprout.impl;

import io.github.ivannavas.sprout.abstrct.AbstractConversationStore;
import io.github.ivannavas.sprout.annotation.ConversationStore;
import io.github.ivannavas.sprout.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link AbstractConversationStore}: keeps history in memory and is not shared across
 * processes. Safe for concurrent use — an agent is a singleton, so a single store instance is shared
 * by every request that reaches it — but history is lost when the JVM stops; use a persistent store
 * (such as a JPA-backed store) when you need durability.
 */
@ConversationStore
public class InMemoryConversationStore implements AbstractConversationStore {

    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();

    @Override
    public List<Message> load(String conversationId) {
        List<Message> messages = conversations.get(conversationId);
        if (messages == null) {
            return new ArrayList<>();
        }
        // Snapshot taken under the list's lock, so a caller never iterates history that another
        // thread is appending to.
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        List<Message> stored = conversations.computeIfAbsent(conversationId, k -> new ArrayList<>());
        synchronized (stored) {
            stored.addAll(messages);
        }
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }
}
