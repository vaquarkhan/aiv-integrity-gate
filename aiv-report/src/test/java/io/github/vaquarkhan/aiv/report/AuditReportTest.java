/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.report;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.util.JsonLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditReportTest {

    private static AIVResult run(boolean passed, GateResult... gates) {
        return new AIVResult(passed, List.of(gates), List.of(
                "[CONFIG-CHANGE] edited .aiv/config.yaml",
                "[SKIP-IGNORED] refused",
                "[SKIP-HONORED] honored",
                "[SUPPRESSED] invariant: 1 finding removed (rules: invariant.placeholder)",
                "plain warning"));
    }

    private static Map<String, Object> meta(String ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", ts);
        m.put("head_sha", "abc");
        return m;
    }

    private static Path seed(Path dir) throws IOException {
        Path log = dir.resolve("audit/aiv.log");
        Finding hard = Finding.atLine("invariant.placeholder", "src/A.java", 3, "TODO left");
        Finding soft = Finding.atLine("test-adequacy.missing-tests", "src\\B.py", 1, "no test");
        AIVResult r1 = run(false, GateResult.fail("invariant", "x", List.of(hard)),
                GateResult.advisory("test-adequacy", "y", List.of(soft)), GateResult.pass("syntax"));
        String[] a1 = AuditRecords.append(log, AuditRecords.gateRun(r1, meta("2026-10-17T10:00:00Z")));
        assertEquals("1", a1[0]);
        AIVResult r2 = new AIVResult(true, List.of(GateResult.advisory("test-adequacy", "y", List.of(soft))), List.of());
        AuditRecords.append(log, AuditRecords.gateRun(r2, Map.of()));
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("schema", "aiv-proposal/1");
        proposal.put("ts", "2026-10-17T11:00:00Z");
        proposal.put("title", "KIP-1 <x>");
        proposal.put("state", "Accepted");
        proposal.put("version", 3);
        proposal.put("rules", List.of("proposal.placeholder"));
        proposal.put("content_sha256", "ff");
        HashChainAudit.append(log, JsonLite.write(proposal));
        HashChainAudit.append(log, "{\"schema\":\"something-else\"}");
        HashChainAudit.append(log, "not json");
        HashChainAudit.append(log, "[1,2]");
        return log;
    }

    @Test
    void recordShapeCarriesOverrideEvidence() {
        Map<String, Object> rec = AuditRecords.gateRun(run(false, GateResult.fail("g", "m",
                List.of(Finding.atLine("g.r", "f", 2, "m")))), Map.of("ts", "t"));
        assertEquals("aiv-gate-run/1", rec.get("schema"));
        assertEquals("t", rec.get("ts"));
        assertEquals(Boolean.FALSE, rec.get("passed"));
        Object o = rec.get("overrides");
        assertEquals(Boolean.TRUE, JsonLite.get(o, "config_change"));
        assertEquals(Boolean.TRUE, JsonLite.get(o, "skip_ignored"));
        assertEquals(Boolean.TRUE, JsonLite.get(o, "skip_honored"));
        assertEquals(List.of("invariant: 1 finding removed (rules: invariant.placeholder)"), JsonLite.get(o, "suppressed"));
        assertEquals("[AUDIT] record #4 appended to a/b.log; chain head h", AuditRecords.anchorNotice("4", Path.of("a\\b.log"), "h"));
        Object none = AuditRecords.overrides(List.of());
        assertEquals(Boolean.FALSE, JsonLite.get(none, "config_change"));
    }

    @Test
    void aggregatesVerifiedChainWithLabels(@TempDir Path dir) throws IOException {
        Path log = seed(dir);
        Path labels = dir.resolve("labels.csv");
        Files.writeString(labels, "rule_id,file,line,verdict\n# comment\n\n"
                + "invariant.placeholder,src/A.java,3,TP\ntest-adequacy.missing-tests,src/B.py,1,fp\n", StandardCharsets.UTF_8);
        AuditReport r = AuditReport.load(log, HashChainAudit.headHash(log), AuditReport.readLabels(labels));
        assertTrue(r.chainIntact());
        assertEquals(6, r.recordCount());
        assertEquals(2, r.gateRuns());
        assertEquals(1, r.passedRuns());
        assertEquals(1, r.proposals().size());
        assertEquals(Map.of("config_change", 1, "skip_ignored", 1, "skip_honored", 1, "suppressed", 1), r.overrides());

        AuditReport.RuleStats hard = r.rules().get("invariant.placeholder");
        assertEquals("invariant.placeholder", hard.ruleId());
        assertEquals(1, hard.findings());
        assertEquals(1, hard.runs());
        assertEquals(1, hard.blocking());
        assertEquals(0, hard.advisory());
        assertEquals(1, hard.truePositives());
        assertEquals(0, hard.falsePositives());
        assertEquals(1, hard.distinctFiles());
        assertEquals("2026-10-17T10:00:00Z", hard.lastSeen());
        assertEquals("needs labels (1/2)", hard.verdict(2));
        assertEquals("blocking, calibrated", hard.verdict(1));

        AuditReport.RuleStats soft = r.rules().get("test-adequacy.missing-tests");
        assertEquals(2, soft.findings());
        assertEquals(2, soft.runs());
        assertEquals(2, soft.falsePositives());
        assertEquals("keep advisory", soft.verdict(1));
        assertTrue(r.chainStatus().startsWith("chain intact: 6 records, head "));

        String html = r.html(1);
        assertTrue(html.contains("blocking, calibrated"));
        assertTrue(html.contains("KIP-1 &lt;x&gt;"));
        assertTrue(html.contains("proposal.placeholder"));
        assertTrue(html.contains("class=\"ok\""));
        assertTrue(html.contains("do not determine whether AI authored"));

        Path out = dir.resolve("export");
        r.exportJsonl(out);
        List<String> recs = Files.readAllLines(out.resolve("records.jsonl"));
        assertEquals(6, recs.size());
        List<String> finds = Files.readAllLines(out.resolve("findings.jsonl"));
        assertEquals(3, finds.size());
        assertEquals("invariant.placeholder", JsonLite.get(JsonLite.parse(finds.get(0)), "rule_id"));
        assertEquals(Boolean.TRUE, JsonLite.get(JsonLite.parse(finds.get(0)), "blocks_ci"));
    }

    @Test
    void verdictCoversEveryBranch() {
        AuditReport.RuleStats s = new AuditReport.RuleStats("r");
        s.blocking = 1;
        s.fp = 1;
        assertEquals("review: false positives on a blocking rule", AuditReport.verdict(s, 1));
        AuditReport.RuleStats a = new AuditReport.RuleStats("a");
        a.advisory = 1;
        a.tp = 5;
        assertEquals("ready to promote", AuditReport.verdict(a, 5));
        a.blocking = 1;
        assertEquals("ready to promote", AuditReport.verdict(a, 5), "mixed blocking/advisory history is not 'calibrated'");
    }

    @Test
    void brokenAndReanchoredChainsAreReported(@TempDir Path dir) throws IOException {
        Path log = seed(dir);
        String head = HashChainAudit.headHash(log);
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        String[] p = lines.get(0).split("\t");
        lines.set(0, p[0] + "\t" + p[1] + "\t" + java.util.Base64.getEncoder().encodeToString("{\"schema\":\"x\"}".getBytes()));
        Files.write(log, lines, StandardCharsets.UTF_8);
        AuditReport broken = AuditReport.load(log, null, null);
        assertFalse(broken.chainIntact());
        assertEquals("chain BROKEN at record index 0 of 6: numbers below come from an unverified log", broken.chainStatus());
        assertTrue(broken.html(5).contains("class=\"bad\""));

        Path fresh = dir.resolve("fresh.log");
        AuditRecords.append(fresh, AuditRecords.gateRun(new AIVResult(true, List.of()), Map.of()));
        AuditReport mismatch = AuditReport.load(fresh, head, List.of());
        assertTrue(mismatch.chainStatus().startsWith("chain head does not match the external anchor"));
        assertTrue(mismatch.html(5).contains("No findings recorded."));
        assertEquals("", AuditReport.esc(null));
    }

    @Test
    void labelValidation(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("l.csv");
        for (String bad : List.of("a,b,c\n", "a,b,1,maybe\n", "a,b,x,tp\n")) {
            Files.writeString(f, bad, StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> AuditReport.readLabels(f), bad);
        }
    }
}
