package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link io.github.ivannavas.sprout.abstrct.AbstractConversationStore} implementation as a
 * managed component, so it can be referenced as an {@link Agent#conversationStore() agent store}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface ConversationStore {
}
