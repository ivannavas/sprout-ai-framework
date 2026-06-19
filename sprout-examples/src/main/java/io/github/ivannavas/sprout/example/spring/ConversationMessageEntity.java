package io.github.ivannavas.sprout.example.spring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One persisted conversation message: its conversation id and the serialised {@code Message} JSON. */
@Entity
@Table(name = "conversation_message")
class ConversationMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String conversationId;

    @Column(nullable = false, length = 10_000)
    private String payload;

    protected ConversationMessageEntity() {
    }

    ConversationMessageEntity(String conversationId, String payload) {
        this.conversationId = conversationId;
        this.payload = payload;
    }

    String getPayload() {
        return payload;
    }
}
