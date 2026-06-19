package io.github.ivannavas.sprout;

import io.github.ivannavas.sprout.container.SproutContainer;

import java.util.logging.Logger;

/**
 * Entry point for a standalone Sprout application. {@code SproutApplication.run(MyApp.class)}
 * bootstraps a {@link SproutContainer}, scanning from the given class's package (unless
 * {@code sprout.scan.base-packages} is set), and returns it for bean look-ups.
 */
public final class SproutApplication {

    private SproutApplication() {
    }

    /** Bootstraps the container, registers a JVM shutdown hook to dispose it, and returns it. */
    public static SproutContainer run(Class<?> mainClass) {
        Logger logger = Logger.getLogger(mainClass.getName());
        logger.info("Sprout starting...");
        SproutContainer container = new SproutContainer(mainClass, logger);
        container.bootstrap();
        Runtime.getRuntime().addShutdownHook(new Thread(container::shutdown, "sprout-shutdown"));
        return container;
    }
}
