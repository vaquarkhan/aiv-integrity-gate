/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Overrides and suppressions must be visible so the audit chain can record them. */
class OrchestratorAuditNoticesTest {

    private static List<AIVConfig.GateConfig> onlyFail() {
        return List.of(
                new AIVConfig.GateConfig("pass", false, Map.of()),
                new AIVConfig.GateConfig("disabled", false, Map.of()),
                new AIVConfig.GateConfig("fail", true, Map.of()),
                new AIVConfig.GateConfig("afterfail", false, Map.of()),
                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                new AIVConfig.GateConfig("doc-integrity", false, Map.of()));
    }

    @Test
    void baselineSuppressionIsReportedAsNotice(@TempDir Path dir) throws Exception {
        Path baseline = dir.resolve("baseline.txt");
        Files.writeString(baseline, "fail.rule|a.txt\n", StandardCharsets.UTF_8);
        var results = new ArrayList<AIVResult>();
        var orch = new Orchestrator((ws, b, h) -> new Diff(b, h, List.of(), ""),
                ws -> new AIVConfig(onlyFail(), Map.of("baseline", baseline.toString())), results::add);
        assertEquals(0, orch.run(dir, "main", "HEAD"));
        String notice = results.get(0).getNotices().stream().filter(n -> n.startsWith("[SUPPRESSED]")).findFirst().orElseThrow();
        assertEquals("[SUPPRESSED] fail: 1 finding removed by baseline or inline aiv-disable (rules: fail.rule)", notice);
    }

    @Test
    void suppressionNoticeShapes() {
        Finding a = Finding.atLine("r.a", "x", 1, "m");
        Finding b = Finding.atLine("r.b", "x", 2, "m");
        GateResult before = GateResult.fail("g", "m", List.of(a, b));
        assertNull(Orchestrator.suppressionNotice("g", before, before));
        assertNull(Orchestrator.suppressionNotice("g", GateResult.pass("g"), GateResult.pass("g")));
        assertEquals("[SUPPRESSED] g: 2 findings removed by baseline or inline aiv-disable (rules: r.a, r.b)",
                Orchestrator.suppressionNotice("g", before, GateResult.pass("g")));
        assertEquals("[SUPPRESSED] g: 1 finding removed by baseline or inline aiv-disable (rules: r.b)",
                Orchestrator.suppressionNotice("g", before, GateResult.fail("g", "m", List.of(a))));
    }

    @Test
    void honoredSkipIsReportedWithIdentity() {
        var results = new ArrayList<AIVResult>();
        var orch = new Orchestrator(
                (ws, b, h) -> new Diff(b, h, List.of(), "", 0, 0, "lead@example.org", true, true, List.of(), Map.of()),
                ws -> new AIVConfig(onlyFail(), Map.of("skip_allowlist", List.of("lead@example.org"))), results::add);
        assertEquals(0, orch.run(Path.of("."), "main", "HEAD"));
        assertTrue(results.get(0).getNotices().stream().anyMatch(n ->
                n.equals("[SKIP-HONORED] /aiv skip honored for lead@example.org (on skip_allowlist, good signature); no gate was evaluated.")),
                results.get(0).getNotices().toString());
    }
}
