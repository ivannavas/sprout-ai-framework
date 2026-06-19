package io.github.ivannavas.sprout.example.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.abstrct.AbstractConversationStore;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.ConversationStore;
import io.github.ivannavas.sprout.model.Message;

import java.util.List;

/**
 * An {@link AbstractConversationStore} backed by a Spring Data JPA repository, persisting history to
 * the in-memory H2 database. It is a Sprout {@code @ConversationStore} that constructor-injects the
 * Spring Data {@link ConversationMessageRepository}: the repository (a Spring bean) flows into this
 * Sprout component — another case of Spring → Sprout. The {@link WeatherAgent} selects it via
 * {@code @Agent(conversationStore = JpaConversationStore.class)}, and the repository's own proxy keeps
 * the writes transactional.
 *
 * <p>Each {@link Message} is stored as a JSON row, so tool calls and tool results round-trip intact.
 */
@ConversationStore
public class JpaConversationStore implements AbstractConversationStore {

    private final ConversationMessageRepository repository;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    JpaConversationStore(ConversationMessageRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Message> load(String conversationId) {
        return repository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(entity -> read(entity.getPayload()))
                .toList();
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            repository.save(new ConversationMessageEntity(conversationId, write(message)));
        }
    }

    @Override
    public void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }

    private String write(Message message) {
        try {
            return json.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise conversation message", e);
        }
    }

    private Message read(String payload) {
        try {
            return json.readValue(payload, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialise conversation message", e);
        }
    }
}
