package io.github.ivannavas.sprout.example.monitoring;

import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.monitoring.AgentUsage;
import io.github.ivannavas.sprout.monitoring.ModelUsage;
import io.github.ivannavas.sprout.monitoring.ToolUsage;
import io.github.ivannavas.sprout.monitoring.UsageSnapshot;
import io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Shows {@code sprout-monitoring} working, fully offline. Monitoring activates automatically once the
 * module is on the classpath: with no {@code @UsageStore} of our own, the shipped in-memory store is
 * installed and a collector is subscribed to the event bus — no package to scan or configure. We run an
 * agent a few times and then read back the accumulated usage: per model, per agent and per tool, with
 * costs derived from a {@code sprout.monitoring.pricing.*} rate we set before bootstrapping.
 */
public final class MonitoringExampleApplication {

    public static void main(String[] args) {
        SproutContainer container = new SproutContainer(
                MonitoringExampleApplication.class, Logger.getLogger(MonitoringExampleApplication.class.getName()));
        // No scan config needed: this example's own package is scanned by default, and sprout-monitoring
        // installs its in-memory @UsageStore automatically (declare your own @UsageStore to persist usage
        // elsewhere).
        // Price the stub model so the report shows non-zero cost (rates are per one million tokens). In a
        // real app these live in sprout.properties; an unpriced model simply reports zero cost.
        container.setProperty("sprout.monitoring.pricing.WeatherStubModel.input", "3.0");
        container.setProperty("sprout.monitoring.pricing.WeatherStubModel.output", "15.0");
        container.bootstrap();

        AgentExecutor weather = container.getSingleton("weatherAgentExecutor");

        System.out.println("== Monitoring: usage, tokens and cost across agent runs ==");
        for (String city : List.of("Madrid", "Paris", "London")) {
            weather.execute("session-" + city, "What's the weather in " + city + "?");
        }

        AbstractUsageStore store = container.getSingleton("usageStore");
        report(store.snapshot());
    }

    private static void report(UsageSnapshot snapshot) {
        System.out.println("Models:");
        for (ModelUsage model : snapshot.byModel().values()) {
            System.out.printf(Locale.ROOT, "  %s: %d calls, %d in + %d out tokens, $%.6f%n",
                    model.modelName(), model.calls(), model.inputTokens(), model.outputTokens(), model.cost());
        }
        System.out.println("Agents:");
        for (AgentUsage agent : snapshot.byAgent().values()) {
            System.out.printf(Locale.ROOT, "  %s: %d runs (%d ok / %d failed), %d iterations, %d tokens%n",
                    agent.agentName(), agent.runs(), agent.completed(), agent.failed(),
                    agent.iterations(), agent.totalTokens());
        }
        System.out.println("Tools:");
        for (ToolUsage tool : snapshot.byTool().values()) {
            System.out.printf(Locale.ROOT, "  %s: %d calls%n", tool.toolName(), tool.calls());
        }
        System.out.printf(Locale.ROOT, "Totals: %d model calls, %d tokens, $%.6f%n",
                snapshot.modelCalls(), snapshot.totalTokens(), snapshot.totalCost());
    }
}
