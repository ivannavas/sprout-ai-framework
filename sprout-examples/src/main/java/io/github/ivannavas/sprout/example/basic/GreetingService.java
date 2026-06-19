package io.github.ivannavas.sprout.example.basic;

import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Qualifier;
import io.github.ivannavas.sprout.annotation.Service;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;

import java.util.List;

@Service
public class GreetingService {

    private final ModelExecutor modelExecutor;

    @Autowired
    public GreetingService(@Qualifier("anthropic") ModelExecutor modelExecutor) {
        this.modelExecutor = modelExecutor;
    }

    public String greet(String name) {
        return modelExecutor.chat(new ModelRequest(List.of(Message.user("Say hello to " + name))))
                .message()
                .content();
    }
}
