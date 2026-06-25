package io.github.ivannavas.sprout.example.rag;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;

import java.util.List;

/**
 * Offline model standing in for a real LLM, so the RAG example needs no API key. It reads the
 * top-ranked passage out of the context the agent prepended to the prompt (the {@code [1] …} block) and
 * answers from it — proving the retrieved knowledge actually reached the model. A real model would write
 * a fluent answer grounded in that same context instead of echoing it.
 */
@Model
public class KnowledgeBaseModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        String prompt = request.messages().stream()
                .filter(m -> m.content() != null)
                .reduce((first, second) -> second).orElseThrow()
                .content();

        String topPassage = firstRetrievedPassage(prompt);
        if (topPassage == null) {
            return answer("I couldn't find anything relevant in the knowledge base.");
        }
        return answer("Based on the knowledge base: " + topPassage);
    }

    // The agent formats retrieved context as "[1] passage\n\n[2] …"; pull out the highest-ranked one.
    private static String firstRetrievedPassage(String prompt) {
        int start = prompt.indexOf("[1] ");
        if (start < 0) {
            return null;
        }
        start += "[1] ".length();
        int end = prompt.indexOf("\n\n", start);
        return prompt.substring(start, end < 0 ? prompt.length() : end).trim();
    }

    private ModelResponse answer(String text) {
        return new ModelResponse(Message.assistant(text), TokenUsage.ZERO, FinishReason.STOP);
    }
}
