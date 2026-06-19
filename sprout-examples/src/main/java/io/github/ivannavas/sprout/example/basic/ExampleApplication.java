package io.github.ivannavas.sprout.example.basic;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;

public final class ExampleApplication {

    // This example must scan its own package plus the sprout-anthropic module so the
    // @Model("anthropic") executor is registered. The `basic` Maven profile supplies this via
    // -Dsprout.scan.base-packages; default it here too so a plain IDE run (no VM flags) works.
    // Any value already set (-D flag or the Maven profile) wins, since we only fill the gap.
    private static final String DEFAULT_SCAN_PACKAGES =
            "io.github.ivannavas.sprout.example.basic,io.github.ivannavas.sprout.anthropic";

    public static void main(String[] args) {
        if (System.getProperty("sprout.scan.base-packages") == null) {
            System.setProperty("sprout.scan.base-packages", DEFAULT_SCAN_PACKAGES);
        }

        SproutContainer container = SproutApplication.run(ExampleApplication.class);

        GreetingService greetingService = container.getSingleton(GreetingService.class);
        System.out.println(greetingService.greet("Sprout"));
    }
}
