/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.util.JsonLite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceCommandsTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @AfterEach
    void restore() {
        EvidenceCommands.CLOCK = Clock.systemUTC();
        Main.GITHUB_ENV = System::getenv;
    }

    private PrintStream o() {
        out.reset();
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    private PrintStream e() {
        err.reset();
        return new PrintStream(err, true, StandardCharsets.UTF_8);
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
    }

    @Test
    void recordWritesChainedRecordWithGitFacts(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "-c", "user.name=Viquar Khan", "-c", "user.email=vaquar.khan@gmail.com", "commit", "-q", "--allow-empty", "-m", "init");
        EvidenceCommands.CLOCK = Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC);
        Path log = repo.resolve("audit/aiv.log");
        AIVResult r = new AIVResult(false, List.of(GateResult.fail("invariant", "m",
                List.of(Finding.atLine("invariant.placeholder", "A.java", 2, "TODO")))), List.of("[SKIP-IGNORED] refused"));
        String pinned = EvidenceCommands.revParse(repo, "HEAD");
        AIVResult anchored = EvidenceCommands.record(log, r, repo, "HEAD", "HEAD", 1, false, "1.0.4", "base-ref", pinned);
        String notice = anchored.getNotices().get(anchored.getNotices().size() - 1);
        assertEquals("[AUDIT] record #1 appended to " + log.toString().replace('\\', '/') + "; chain head " + HashChainAudit.headHash(log), notice);
        assertEquals(2, anchored.getNotices().size());
        Object rec = JsonLite.parse(HashChainAudit.payloads(log).get(0));
        assertEquals("aiv-gate-run/1", JsonLite.get(rec, "schema"));
        assertEquals("2026-10-17T09:00:00Z", JsonLite.get(rec, "ts"));
        assertEquals(40, JsonLite.str(JsonLite.get(rec, "head_sha")).length());
        assertEquals(pinned, JsonLite.get(rec, "base_sha"));
        assertEquals(pinned, JsonLite.get(rec, "policy_commit"));
        assertEquals("vaquar.khan@gmail.com", JsonLite.get(rec, "author"));
        assertEquals("base-ref", JsonLite.get(rec, "config_source"));
        assertEquals(1L, JsonLite.get(rec, "exit_code"));
        assertEquals(Boolean.TRUE, JsonLite.get(rec, "overrides", "skip_ignored"));

        assertNull(EvidenceCommands.revParse(repo, "no-such-ref"));
        assertNull(EvidenceCommands.git(repo, "rev-parse", "--show-prefix"), "empty output is null");
        assertNull(EvidenceCommands.git(repo.resolve("missing-dir"), "status"), "git cannot start in a missing directory");
    }

    @Test
    void auditVerifyCoversIntactAnchoredAndTampered(@TempDir Path dir) throws IOException {
        assertEquals(2, EvidenceCommands.audit(new String[] {}, o(), e()));
        assertEquals(2, EvidenceCommands.audit(new String[] {"show", "x"}, o(), e()));
        assertEquals(2, EvidenceCommands.audit(new String[] {"verify", dir.resolve("none.log").toString()}, o(), e()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("audit log not found"));
        Path log = dir.resolve("a.log");
        HashChainAudit.append(log, "{\"a\":1}");
        HashChainAudit.append(log, "{\"a\":2}");
        String head = HashChainAudit.headHash(log);
        assertEquals(2, EvidenceCommands.audit(new String[] {"verify", log.toString(), "--bogus"}, o(), e()));
        assertEquals(0, EvidenceCommands.audit(new String[] {"verify", log.toString()}, o(), e()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("intact: 2 records, head " + head + " (no external anchor given)"));
        assertEquals(0, EvidenceCommands.audit(new String[] {"verify", log.toString(), "--expect-head", head}, o(), e()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("(matches anchor)"));
        assertEquals(1, EvidenceCommands.audit(new String[] {"verify", log.toString(), "--expect-head", "0".repeat(64)}, o(), e()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("does not match the anchor"));
        List<String> lines = Files.readAllLines(log);
        Files.write(log, List.of(lines.get(1)));
        assertEquals(1, EvidenceCommands.audit(new String[] {"verify", log.toString()}, o(), e()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("TAMPERED: chain breaks at record index 0 of 1"));
        Path dirAsLog = dir.resolve("d.log");
        Files.createDirectory(dirAsLog);
        assertEquals(2, EvidenceCommands.audit(new String[] {"verify", dirAsLog.toString()}, o(), e()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot read audit log"));
    }

    @Test
    void reportWritesHtmlExportsAndFlagsBrokenChains(@TempDir Path dir) throws IOException {
        assertEquals(2, EvidenceCommands.report(new String[] {}, o(), e()));
        assertEquals(2, EvidenceCommands.report(new String[] {"--out"}, o(), e()));
        assertEquals(2, EvidenceCommands.report(new String[] {"--audit-log", dir.resolve("none").toString()}, o(), e()));
        Path log = dir.resolve("a.log");
        EvidenceCommands.CLOCK = Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC);
        EvidenceCommands.record(log, new AIVResult(true, List.of(GateResult.advisory("cohesion", "m",
                List.of(Finding.atLine("cohesion.areas", "(diff)", 1, "4 areas"))))), dir, "main", "HEAD", 0, false, "v", "workspace", null);
        Path labels = dir.resolve("labels.csv");
        Files.writeString(labels, "cohesion.areas,(diff),1,tp\n", StandardCharsets.UTF_8);
        Path html = dir.resolve("out/report.html");
        assertEquals(0, EvidenceCommands.report(new String[] {"--audit-log", log.toString(), "--out", html.toString(),
                "--labels", labels.toString(), "--export-jsonl", dir.resolve("jsonl").toString(), "--min-labeled", "1"}, o(), e()));
        String page = Files.readString(html);
        assertTrue(page.contains("ready to promote"), page);
        assertTrue(Files.exists(dir.resolve("jsonl/findings.jsonl")));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("gate runs 1, rules 1, proposal checks 0; wrote "));

        assertEquals(2, EvidenceCommands.report(new String[] {"--audit-log", log.toString(), "--min-labeled", "x"}, o(), e()));
        assertEquals(2, EvidenceCommands.report(new String[] {"--audit-log", log.toString(), "--labels", log.toString()}, o(), e()));
        assertEquals(2, EvidenceCommands.report(new String[] {"--audit-log", log.toString(), "--labels", dir.resolve("nope.csv").toString()}, o(), e()));
        assertEquals(1, EvidenceCommands.report(new String[] {"--audit-log", log.toString(), "--out", html.toString(),
                "--expect-head", "0".repeat(64)}, o(), e()));
        Files.writeString(log, "garbage-without-tabs\n", StandardCharsets.UTF_8);
        assertEquals(2, EvidenceCommands.report(new String[] {"--audit-log", log.toString(), "--out", html.toString()}, o(), e()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("aiv report: audit line 0"));
    }

    @Test
    void mainWiresAuditLogAndSubcommands(@TempDir Path ws) throws Exception {
        Main.GITHUB_ENV = k -> null;
        Path json = ws.resolve("proposed.json");
        Files.writeString(json, "{\"files\":[{\"path\":\"Ok.java\",\"changeType\":\"ADDED\",\"content\":\"class Ok {}\\n\"}]}",
                StandardCharsets.UTF_8);
        Files.createDirectories(ws.resolve(".aiv"));
        Files.writeString(ws.resolve(".aiv/config.yaml"), "schema_version: 1\naudit_log: .aiv/audit/aiv.log\n"
                + "gates:\n  - id: syntax\n    enabled: true\n  - id: test-adequacy\n    enabled: false\n"
                + "  - id: package-existence\n    enabled: false\n  - id: duplication\n    enabled: false\n"
                + "  - id: density\n    enabled: false\n  - id: cohesion\n    enabled: false\n  - id: design\n    enabled: false\n"
                + "  - id: dependency\n    enabled: false\n  - id: invariant\n    enabled: false\n", StandardCharsets.UTF_8);
        Path report = ws.resolve("r.json");
        assertEquals(0, Main.run(new String[] {"--workspace", ws.toString(), "--diff-json", json.toString(), "--output-json", report.toString()}));
        Path configured = ws.resolve(".aiv/audit/aiv.log");
        assertEquals(1, HashChainAudit.payloads(configured).size());
        assertTrue(Files.readString(report).contains("[AUDIT] record #1 appended to "));

        Path flagLog = ws.resolve("flag.log");
        assertEquals(0, Main.run(new String[] {"--workspace", ws.toString(), "--diff-json", json.toString(), "--quiet",
                "--audit-log", flagLog.toString()}));
        assertEquals(1, HashChainAudit.payloads(flagLog).size());
        assertEquals(1, HashChainAudit.payloads(configured).size(), "the CLI flag wins over the config key");

        assertEquals(0, Main.run(new String[] {"audit", "verify", flagLog.toString()}));
        assertEquals(0, Main.run(new String[] {"report", "--audit-log", flagLog.toString(), "--out", ws.resolve("r.html").toString()}));
        assertEquals(2, Main.run(new String[] {"proposal"}));
    }
}
