package io.github.ivannavas.sprout.mcp.itest;

import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;

/** A trivial model used only to let the combined {@code @Agent @Mcp} fixture wire up. */
@Model
public class EchoModel extends ModelExecutor {

    @Override
    public ModelResponse chat(ModelRequest request) {
        return new ModelResponse(Message.assistant("ok"), TokenUsage.ZERO, FinishReason.STOP);
    }
}
