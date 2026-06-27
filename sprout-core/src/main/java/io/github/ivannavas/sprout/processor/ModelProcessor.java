package io.github.ivannavas.sprout.processor;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.annotation.Model;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.ModelExecutor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Processor for {@link Model @Model} components. Validates that they extend {@link ModelExecutor}
 * and, when {@code @Model("name")} is set, registers the executor under that extra bean name.
 */
@Processor(Model.class)
public class ModelProcessor extends ComponentProcessor {

    public ModelProcessor(Class<?> component, SproutContainer sproutContainer) {
        super(component, sproutContainer);
    }

    @Override
    public void validate() {
        super.validate();
        if (!ModelExecutor.class.isAssignableFrom(component)) {
            throw new IllegalArgumentException("@Model " + component + " must extend ModelExecutor");
        }
    }

    @Override
    public Object instantiate() {
        Object instance = super.instantiate();
        ((ModelExecutor) instance).setEventBus(
                (AbstractEventBus) sproutContainer.getOrCreateByType(AbstractEventBus.class));
        return instance;
    }

    @Override
    public Set<String> beanNames() {
        Set<String> names = new LinkedHashSet<>(super.beanNames());
        String declared = component.getAnnotation(Model.class).value();
        if (!declared.isBlank()) {
            names.add(declared);
        }
        return names;
    }
}
