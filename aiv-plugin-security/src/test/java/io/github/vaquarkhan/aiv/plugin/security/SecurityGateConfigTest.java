/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.security;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repository-specific configuration of the security gate. */
class SecurityGateConfigTest {

    private static final String AWS = "key = \"AKIAABCDEFGHIJKLMNOP\"\n";

    private static AIVContext ctx(Map<String, Object> cfg, String path, String content) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig("security", true, cfg)), Map.of());
        return new AIVContext(Paths.get("."), new Diff("main", "HEAD",
                List.of(new ChangedFile(path, ChangedFile.ChangeType.ADDED, content)), ""), config);
    }

    @Test
    void disabledRulesSuppressBuiltInDetectors() {
        var gate = new SecurityGate();
        assertFalse(gate.evaluate(ctx(Map.of(), "a.env", AWS)).isPassed());
        assertTrue(gate.evaluate(ctx(Map.of("disabled_rules", List.of("security.aws-key")), "a.env", AWS)).isPassed());
    }

    @Test
    void allowPatternsIgnoreMatchingLines() {
        var gate = new SecurityGate();
        Map<String, Object> cfg = Map.of("allow_patterns", List.of("EXAMPLE", "fixtures/"));
        assertTrue(gate.evaluate(ctx(cfg, "a.env", "# EXAMPLE " + AWS)).isPassed());
        assertFalse(gate.evaluate(ctx(cfg, "a.env", AWS)).isPassed());
        assertThrows(IllegalArgumentException.class,
                () -> gate.evaluate(ctx(Map.of("allow_patterns", List.of("(")), "a.env", AWS)));
    }

    @Test
    void extraPatternsAddProjectSpecificRules() {
        var gate = new SecurityGate();
        Map<String, Object> extra = new HashMap<>();
        extra.put("Internal-Token", "\\bINT-[A-Z0-9]{8}\\b");
        extra.put(null, "ignored");
        extra.put("nullvalue", null);
        GateResult r = gate.evaluate(ctx(Map.of("extra_patterns", extra), "cfg.yaml", "token: INT-ABCDEFGH\n"));
        assertFalse(r.isPassed());
        assertEquals("security.internal-token", r.getFindings().get(0).getRuleId());
        assertTrue(r.getMessage().contains("internal-token"));
        assertThrows(IllegalArgumentException.class,
                () -> gate.evaluate(ctx(Map.of("extra_patterns", Map.of("bad", "[")), "a", "x")));
    }

    @Test
    void entropyThresholdIsConfigurable() {
        var gate = new SecurityGate();
        String line = "password = \"aB3$kL9#mQ2&xZ7!pR4@wN6^\"\n";
        assertFalse(gate.evaluate(ctx(Map.of(), "a.py", line)).isPassed());
        // raise the bar above what this value reaches and it passes
        assertTrue(gate.evaluate(ctx(Map.of("entropy_threshold", 7.9), "a.py", line)).isPassed());
        // disabling the rule has the same effect
        assertTrue(gate.evaluate(ctx(Map.of("disabled_rules", List.of("high-entropy-secret")), "a.py", line)).isPassed());
    }

    @Test
    void helpers() {
        assertEquals(List.of("a"), SecurityGate.strings(Arrays.asList(" a ", null, " ")));
        assertTrue(SecurityGate.strings(42).isEmpty());
        var s = SecurityGate.Settings.from(Map.of());
        assertEquals(SecurityGate.ENTROPY_THRESHOLD, s.entropyThreshold());
        assertFalse(s.allowed("anything"));
    }
}
