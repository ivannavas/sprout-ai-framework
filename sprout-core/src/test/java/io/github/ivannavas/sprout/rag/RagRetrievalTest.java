package io.github.ivannavas.sprout.rag;

import io.github.ivannavas.sprout.abstrct.AbstractVectorStore;
import io.github.ivannavas.sprout.embedding.EmbeddingModel;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.impl.HashingEmbeddingModel;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.impl.InMemoryVectorStore;
import io.github.ivannavas.sprout.model.AgentData;
import io.github.ivannavas.sprout.model.Document;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.SearchResult;
import io.github.ivannavas.sprout.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalTest {

    /** Echoes back the prompt it received, so the test can inspect what the model was shown. */
    static class EchoPromptModel extends ModelExecutor {
        @Override
        public ModelResponse chat(ModelRequest request) {
            String lastUser = request.messages().stream()
                    .filter(m -> m.content() != null)
                    .reduce((first, second) -> second).orElseThrow()
                    .content();
            return new ModelResponse(Message.assistant(lastUser), TokenUsage.ZERO, FinishReason.STOP);
        }
    }

    @Test
    void ranksRelevantDocumentHighestAndAugmentsThePrompt() {
        EmbeddingModel embedding = new HashingEmbeddingModel();
        AbstractVectorStore store = new InMemoryVectorStore();
        Retriever retriever = new Retriever(embedding, store, 2);

        retriever.index(List.of(
                Document.of("1", "The Eiffel Tower is located in Paris, France."),
                Document.of("2", "Mount Everest is the tallest mountain on Earth."),
                Document.of("3", "Bananas are a good source of potassium.")));

        List<SearchResult> results = retriever.retrieve("Where is the Eiffel Tower?");
        assertFalse(results.isEmpty(), "retrieval should return documents");
        assertEquals("1", results.get(0).document().id(), "the Paris document should rank first");

        AtomicReference<String> promptSeenByModel = new AtomicReference<>();
        ModelExecutor model = new EchoPromptModel() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                ModelResponse response = super.chat(request);
                promptSeenByModel.set(response.message().content());
                return response;
            }
        };

        AgentExecutor agent = new AgentExecutor();
        agent.configure(new AgentData(model, new InMemoryConversationStore(), retriever, "", 3, Map.of()));
        agent.execute("session", "Where is the Eiffel Tower?");

        assertTrue(promptSeenByModel.get().contains("Eiffel Tower is located in Paris"),
                "the model should see the retrieved context prepended to the prompt");
        assertTrue(promptSeenByModel.get().contains("Question: Where is the Eiffel Tower?"),
                "the original question should follow the context");
    }

    @Test
    void withoutARetrieverThePromptIsUntouched() {
        AtomicReference<String> promptSeenByModel = new AtomicReference<>();
        ModelExecutor model = new EchoPromptModel() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                ModelResponse response = super.chat(request);
                promptSeenByModel.set(response.message().content());
                return response;
            }
        };

        AgentExecutor agent = new AgentExecutor();
        agent.configure(new AgentData(model, new InMemoryConversationStore(), null, "", 3, Map.of()));
        agent.execute("session", "plain question");

        assertEquals("plain question", promptSeenByModel.get(), "no RAG means the prompt is sent verbatim");
    }
}
