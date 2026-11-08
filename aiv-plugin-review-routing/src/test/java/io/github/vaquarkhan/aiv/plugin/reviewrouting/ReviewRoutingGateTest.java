/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.reviewrouting;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewRoutingGateTest {

    private static final List<Object> RULES = List.of(
            Map.of("tier", "none", "paths", List.of(".github/workflows/**", "SECURITY.md")),
            Map.of("tier", "balanced", "min_lines_changed", 400),
            Map.of("tier", "balanced", "min_files_changed", 3),
            Map.of("tier", "balanced", "paths", List.of("core/**"), "min_lines_changed", 50));

    private static AIVContext ctx(Map<String, Object> cfg, int added, int deleted, ChangedFile... files) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig("review-routing", true, cfg)), Map.of());
        Diff d = new Diff("main", "HEAD", List.of(files), "", added, deleted, null, false);
        return new AIVContext(Paths.get("."), d, config);
    }

    private static ChangedFile f(String path) {
        return new ChangedFile(path, ChangedFile.ChangeType.MODIFIED, "x\n");
    }

    private static String tier(GateResult r) {
        return ReviewRoutingGate.tierFromMessage(r.getFindings().get(0).getMessage());
    }

    private static GateResult run(Map<String, Object> cfg, int added, int deleted, ChangedFile... files) {
        GateResult r = new ReviewRoutingGate().evaluate(ctx(cfg, added, deleted, files));
        assertTrue(r.isPassed(), "routing never fails");
        assertFalse(r.blocksCi());
        assertEquals(1, r.getFindings().size());
        assertEquals(ReviewRoutingGate.RULE_ID, r.getFindings().get(0).getRuleId());
        assertEquals("(diff)", r.getFindings().get(0).getFilePath());
        return r;
    }

    @Test
    void idAndDefaultTier() {
        assertEquals("review-routing", new ReviewRoutingGate().getId());
        GateResult r = run(Map.of(), 10, 2, f("a.py"));
        assertEquals("lite", tier(r));
        assertTrue(r.getMessage().contains("default tier; lines=12; files=1"), r.getMessage());
    }

    @Test
    void firstMatchingRuleWins() {
        Map<String, Object> cfg = Map.of("default_tier", "lite", "tiers", RULES);
        GateResult none = run(cfg, 900, 0, f("src/a.py"), f(".github/workflows/ci.yml"));
        assertEquals("none", tier(none));
        assertTrue(none.getMessage().contains("rule 0: path .github/workflows/ci.yml"), none.getMessage());

        GateResult big = run(cfg, 300, 100, f("src/a.py"));
        assertEquals("balanced", tier(big));
        assertTrue(big.getMessage().contains("rule 1: 400 lines changed >= 400"), big.getMessage());

        GateResult wide = run(cfg, 1, 0, f("a"), f("b"), f("c"));
        assertEquals("balanced", tier(wide));
        assertTrue(wide.getMessage().contains("3 files changed >= 3"));

        assertEquals("lite", tier(run(cfg, 49, 0, f("core/x.py"))), "all conditions of a rule must hold");
        GateResult core = run(cfg, 50, 0, f("core/x.py"));
        assertEquals("balanced", tier(core));
        assertTrue(core.getMessage().contains("rule 3: path core/x.py, 50 lines changed >= 50"), core.getMessage());
    }

    @Test
    void excludedFilesAndRenameSourcesCount() {
        Map<String, Object> cfg = Map.of("tiers", RULES);
        AIVContext base = ctx(cfg, 1, 0, f("src/a.py"));
        GateResult r = new ReviewRoutingGate().evaluate(base.withMetadata(AIVContext.EXCLUDED_CHANGED_FILES,
                List.of("ignored", f("SECURITY.md"))));
        assertEquals("none", tier(r));

        ChangedFile renamed = new ChangedFile("docs/old-security.md", ChangedFile.ChangeType.RENAMED, "x\n",
                "SECURITY.md", "100644", "100644", null, null, ChangedFile.ObjectType.BLOB,
                ChangedFile.SourceCompleteness.COMPLETE, ChangedFile.Provenance.TRUSTED_GIT_ADAPTER);
        assertEquals("none", tier(run(cfg, 1, 1, renamed)));
        GateResult blank = run(cfg, -5, -5, new ChangedFile(" ", ChangedFile.ChangeType.ADDED, ""));
        assertEquals("lite", tier(blank));
        assertTrue(blank.getMessage().endsWith("lines=0; files=0"), blank.getMessage());
    }

    @Test
    void rejectsInvalidConfiguration() {
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(Map.of("default_tier", "Lite"));
        bad.add(Map.of("default_tier", "a b"));
        bad.add(Map.of("tiers", "nope"));
        bad.add(Map.of("tiers", List.of("nope")));
        bad.add(Map.of("tiers", List.of(Map.of("tier", "x"))));
        bad.add(Map.of("tiers", List.of(Map.of("paths", List.of("a")))));
        bad.add(Map.of("tiers", List.of(Map.of("tier", "x", "min_lines_changed", -1))));
        bad.add(Map.of("tiers", List.of(Map.of("tier", "x", "min_lines_changed", 1.5))));
        bad.add(Map.of("tiers", List.of(Map.of("tier", "x", "min_files_changed", "10"))));
        bad.add(Map.of("tiers", List.of(Map.of("tier", "x", "paths", "a/**"))));
        for (Map<String, Object> cfg : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ReviewRoutingGate().evaluate(ctx(cfg, 1, 0, f("a"))), cfg.toString());
        }
        // Blank path entries are ignored; a rule left with no condition is rejected.
        Map<String, Object> blankPaths = Map.of("tiers",
                List.of(Map.of("tier", "x", "paths", java.util.Arrays.asList(" ", null))));
        assertThrows(IllegalArgumentException.class,
                () -> new ReviewRoutingGate().evaluate(ctx(blankPaths, 1, 0, f("a"))));
    }

    @Test
    void tierFromMessageHandlesOddInput() {
        assertEquals("", ReviewRoutingGate.tierFromMessage(null));
        assertEquals("", ReviewRoutingGate.tierFromMessage("nope"));
        assertEquals("balanced", ReviewRoutingGate.tierFromMessage("tier=balanced"));
        assertEquals("none", ReviewRoutingGate.tierFromMessage("tier= none ; x"));
    }

    @Test
    void worksWithoutGateConfigEntry() {
        var config = new AIVConfig(List.of(), Map.of());
        GateResult r = new ReviewRoutingGate().evaluate(new AIVContext(Paths.get("."),
                new Diff("a", "b", List.of(f("x")), ""), config));
        assertEquals("lite", ReviewRoutingGate.tierFromMessage(r.getMessage()));
    }
}
