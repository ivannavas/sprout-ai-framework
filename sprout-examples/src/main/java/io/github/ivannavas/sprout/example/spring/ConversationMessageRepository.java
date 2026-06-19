package io.github.ivannavas.sprout.example.spring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Spring Data JPA repository over {@link ConversationMessageEntity}. */
interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, Long> {

    List<ConversationMessageEntity> findByConversationIdOrderByIdAsc(String conversationId);

    @Transactional
    void deleteByConversationId(String conversationId);
}
