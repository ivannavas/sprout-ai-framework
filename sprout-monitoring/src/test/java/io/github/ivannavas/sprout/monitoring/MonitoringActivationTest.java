package io.github.ivannavas.sprout.monitoring;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore;
import io.github.ivannavas.sprout.monitoring.custom.CountingUsageStore;
import io.github.ivannavas.sprout.monitoring.custom.CustomStoreApp;
import io.github.ivannavas.sprout.monitoring.impl.InMemoryUsageStore;
import io.github.ivannavas.sprout.monitoring.itest.MonitoringTestApp;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end: the {@code @UsageStore} is wired by its processor like any other component. Scanning the
 * module's in-memory store package registers the default store and subscribes the collector, so an agent
 * run is fully recorded; a scanned {@code @UsageStore} of your own replaces the default.
 */
class MonitoringActivationTest {

    @Test
    void scanningTheDefaultStorePackageWiresMonitoringAndRecordsAnAgentRun() {
        SproutContainer container = new SproutContainer(
                MonitoringTestApp.class, Logger.getLogger(MonitoringActivationTest.class.getName()));
        container.setProperty("sprout.scan.base-packages",
                "io.github.ivannavas.sprout.monitoring.itest,io.github.ivannavas.sprout.monitoring.impl");
        container.bootstrap();

        AbstractUsageStore store = container.getSingleton("usageStore");
        assertNotNull(store, "the in-memory store is registered under the usageStore name");
        assertInstanceOf(InMemoryUsageStore.class, store);

        AgentExecutor agent = container.getSingleton("monitoredAgentExecutor");
        agent.execute("session", "hello");

        UsageSnapshot snapshot = store.snapshot();
        // Two model turns (tool request + final answer): 10+20 input, 5+7 output.
        assertEquals(2, snapshot.modelCalls());
        assertEquals(30, snapshot.inputTokens());
        assertEquals(12, snapshot.outputTokens());

        AgentUsage agentUsage = snapshot.byAgent().get("MonitoredAgent");
        assertNotNull(agentUsage);
        assertEquals(1, agentUsage.completed());
        assertEquals(0, agentUsage.failed());

        assertEquals(1, snapshot.byTool().get("ping").calls());
    }

    @Test
    void monitoringAutoActivatesWithoutScanningTheStorePackage() {
        // Only the app's agent package is scanned — NOT the store's impl package. MonitoringInitializer
        // installs the in-memory default automatically because no @UsageStore was declared.
        SproutContainer container = new SproutContainer(
                MonitoringTestApp.class, Logger.getLogger(MonitoringActivationTest.class.getName()));
        container.setProperty("sprout.scan.base-packages", "io.github.ivannavas.sprout.monitoring.itest");
        container.bootstrap();

        AbstractUsageStore store = container.getSingleton("usageStore");
        assertInstanceOf(InMemoryUsageStore.class, store, "the default store is installed with no scanning");

        AgentExecutor agent = container.getSingleton("monitoredAgentExecutor");
        agent.execute("session", "hello");

        UsageSnapshot snapshot = store.snapshot();
        assertEquals(2, snapshot.modelCalls());
        assertEquals(30, snapshot.inputTokens());
        assertEquals(12, snapshot.outputTokens());
    }

    @Test
    void aScannedUsageStoreReplacesTheDefault() {
        SproutContainer container = SproutApplication.run(CustomStoreApp.class);

        AbstractUsageStore store = container.getSingleton("usageStore");
        assertInstanceOf(CountingUsageStore.class, store);
    }
}
