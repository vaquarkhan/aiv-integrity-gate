/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorSeverityTest {

    @Test
    void warnSeverityTurnsFailureIntoAdvisory() {
        var raw = GateResult.fail("density", "too low");
        var cfg = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of(), "warn")),
                Map.of());
        var adj = Orchestrator.applySeverity(raw, "density", cfg);
        assertFalse(adj.isPassed());
        assertFalse(adj.blocksCi());
    }

    @Test
    void failSeverityKeepsBlocking() {
        var raw = GateResult.fail("density", "too low");
        var cfg = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of(), "fail")),
                Map.of());
        var adj = Orchestrator.applySeverity(raw, "density", cfg);
        assertFalse(adj.isPassed());
        assertTrue(adj.blocksCi());
    }

    @Test
    void applySeverityLeavesPassUnchanged() {
        var p = GateResult.pass("g");
        var cfg = new AIVConfig(
                List.of(new AIVConfig.GateConfig("g", true, Map.of(), "warn")),
                Map.of());
        assertSame(p, Orchestrator.applySeverity(p, "g", cfg));
    }

    @Test
    void applySeverityLeavesAdvisoryUnchanged() {
        var a = GateResult.advisory("g", "x");
        var cfg = new AIVConfig(List.of(), Map.of());
        assertSame(a, Orchestrator.applySeverity(a, "g", cfg));
    }
}
