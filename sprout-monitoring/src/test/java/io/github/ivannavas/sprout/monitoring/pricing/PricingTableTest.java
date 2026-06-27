package io.github.ivannavas.sprout.monitoring.pricing;

import io.github.ivannavas.sprout.monitoring.pricing.PricingTable.Rate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingTableTest {

    @Test
    void unpricedModelCostsNothing() {
        assertEquals(0.0, new PricingTable().costOf("Unknown", 1_000_000, 1_000_000), 1e-9);
    }

    @Test
    void appliesRatePerMillionTokens() {
        PricingTable table = new PricingTable();
        table.put("claude", new Rate(3.0, 15.0));
        // 1M input at 3.0 + 2M output at 15.0
        assertEquals(3.0 + 30.0, table.costOf("claude", 1_000_000, 2_000_000), 1e-9);
        // partial millions scale linearly
        assertEquals(1.5, table.costOf("claude", 500_000, 0), 1e-9);
    }

    @Test
    void loadsRatesFromProperties() {
        PricingTable table = PricingTable.fromProperties(Map.of(
                "sprout.monitoring.pricing.claude.input", "3",
                "sprout.monitoring.pricing.claude.output", "15",
                "sprout.monitoring.enabled", "true",
                "unrelated.key", "ignored"));

        assertEquals(1.5, table.costOf("claude", 500_000, 0), 1e-9);
        assertEquals(15.0, table.costOf("claude", 0, 1_000_000), 1e-9);
        assertEquals(0.0, table.costOf("gpt", 1_000_000, 1_000_000), 1e-9);
    }

    @Test
    void ignoresUnparseableRates() {
        PricingTable table = PricingTable.fromProperties(Map.of(
                "sprout.monitoring.pricing.claude.input", "not-a-number"));
        assertEquals(0.0, table.costOf("claude", 1_000_000, 0), 1e-9);
    }
}
