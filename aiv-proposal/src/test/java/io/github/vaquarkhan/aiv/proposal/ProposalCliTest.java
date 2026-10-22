/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.util.JsonLite;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalCliTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    private int run(Sources.Http http, String... args) {
        outBuf.reset();
        errBuf.reset();
        return ProposalCli.run(args, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8), http, CLOCK);
    }

    private String out() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    /** A wiki with KIP-1 (good, accepted), KIP-2 (template copy) and a search API. */
    private static Sources.Http wiki(AtomicReference<String> kip1Storage) {
        return url -> {
            if (url.contains("/content/search")) {
                return new Sources.Response(200, "{\"results\":[{\"id\":\"2\",\"title\":\"KIP-2: copy\"},"
                        + "{\"id\":\"1\",\"title\":\"KIP-1: good\"},{\"id\":\"9\",\"title\":\"KIP-899: old\"}]}");
            }
            if (url.contains("/content/2?")) {
                return new Sources.Response(200, ProposalFixtures.page("2", "KIP-2: copy", 1, ProposalFixtures.SLOP_KIP));
            }
            if (url.contains("/content/9?")) {
                return new Sources.Response(200, ProposalFixtures.page("9", "KIP-899: old", 1, ProposalFixtures.GOOD_KIP));
            }
            if (url.contains("title=")) {
                return new Sources.Response(200, "{\"results\":[" + ProposalFixtures.page("1", "KIP-1: good", 4, kip1Storage.get()) + "]}");
            }
            return new Sources.Response(200, ProposalFixtures.page("1", "KIP-1: good", 4, kip1Storage.get()));
        };
    }

    @Test
    void usageErrors(@TempDir Path dir) {
        Sources.Http none = url -> { throw new IOException("offline"); };
        assertEquals(2, ProposalCli.run(new String[] {}), "the public entry point needs no network for usage errors");
        assertEquals(2, run(none));
        assertEquals(2, run(none, "other"));
        assertEquals(2, run(none, "check", "stray"));
        assertTrue(err().contains("unexpected argument 'stray'"));
        assertEquals(2, run(none, "check", "--template"));
        assertTrue(err().contains("missing value for --template"));
        assertEquals(2, run(none, "check", "--template", "nope", "--file", "x"));
        assertEquals(2, run(none, "check", "--template", "kip"));
        assertTrue(err().contains("check needs a source"));
        assertEquals(2, run(none, "scan", "--template", "kip", "--space", "KAFKA"));
        assertEquals(2, run(none, "scan", "--template", "spip", "--space", "S", "--last", "1", "--out", dir.toString()));
        assertTrue(err().contains("has no proposal number pattern"));
        assertEquals(2, run(none, "check", "--template", "kip", "--jira", "K-1", "--index"));
        assertTrue(err().contains("--index needs a Confluence space"));
        assertEquals(3, run(none, "check", "--template", "kip", "--confluence-url", "https://c/display/KAFKA/KIP-1"));
        assertTrue(err().contains("could not read source: offline"));
        assertEquals(3, run(url -> { throw new InterruptedException(); }, "check", "--template", "kip", "--jira", "K-1"));
        assertTrue(Thread.interrupted());
    }

    @Test
    void checkFileWritesJsonAndAuditAndDetectsChangeAfterAcceptance(@TempDir Path dir) throws IOException {
        AtomicReference<String> kip1 = new AtomicReference<>(ProposalFixtures.GOOD_KIP);
        Path audit = dir.resolve("audit/proposals.log");
        Path json = dir.resolve("out/r.json");
        assertEquals(0, run(wiki(kip1), "check", "--template", "kip", "--space", "KAFKA", "--title", "KIP-1: good",
                "--index", "--audit-log", audit.toString(), "--output-json", json.toString()));
        assertTrue(out().contains("=== KIP-1: good ===\n"), out());
        assertTrue(out().contains("  no findings\n"));
        assertTrue(out().contains("[AUDIT] 1 proposal record(s) appended to "));
        Object report = JsonLite.parse(Files.readString(json));
        assertEquals("aiv-proposal-report/1", JsonLite.get(report, "schema"));
        assertEquals(HashChainAudit.headHash(audit), JsonLite.get(report, "audit_head"));
        Object rec = JsonLite.parse(HashChainAudit.payloads(audit).get(0));
        assertEquals("2026-10-17T12:00:00Z", JsonLite.get(rec, "ts"));
        assertEquals(Boolean.TRUE, JsonLite.get(rec, "accepted"));
        assertEquals("1", JsonLite.get(rec, "id"));

        kip1.set(ProposalFixtures.GOOD_KIP.replace("Rejected because it delays recovery.", "Rejected because it is slower."));
        assertEquals(1, run(wiki(kip1), "check", "--template", "kip", "--confluence-url",
                "https://cwiki.apache.org/confluence/display/KAFKA/KIP-1", "--audit-log", audit.toString(), "--fail-on-findings"));
        assertTrue(out().contains("proposal.changed-after-acceptance: Content (status section excluded) changed after it was first"
                + " recorded as accepted at version 4"), out());
        assertEquals(2, HashChainAudit.payloads(audit).size());

        assertEquals(0, run(wiki(kip1), "check", "--template", "kip", "--confluence-url",
                "https://cwiki.apache.org/confluence/display/KAFKA/KIP-1", "--index", "--quiet"));
        assertEquals("", out());

        List<String> lines = Files.readAllLines(audit);
        lines.set(0, lines.get(0).replaceFirst("^.", "f"));
        Files.write(audit, lines);
        assertEquals(2, run(wiki(kip1), "check", "--template", "kip", "--space", "KAFKA", "--title", "KIP-1: good",
                "--audit-log", audit.toString()));
        assertTrue(err().contains("refusing to compare against tampered history"), err());
    }

    @Test
    void scanWritesSummaryAndOtherSources(@TempDir Path dir) throws IOException {
        AtomicReference<String> kip1 = new AtomicReference<>(ProposalFixtures.GOOD_KIP);
        Path out = dir.resolve("scan");
        Path audit = dir.resolve("a.log");
        HashChainAudit.append(audit, "{\"schema\":\"aiv-gate-run/1\"}");
        assertEquals(0, run(wiki(kip1), "scan", "--template", "kip", "--space", "KAFKA", "--last", "2", "--index",
                "--out", out.toString(), "--audit-log", audit.toString()));
        String summary = Files.readString(out.resolve("SUMMARY.md"));
        assertTrue(summary.startsWith("# Proposal scan: kip in KAFKA\n"));
        assertTrue(summary.contains("Proposals scanned: 2. With at least one finding: 1."));
        assertTrue(summary.contains("| `proposal.template-text` | 1 |"));
        assertTrue(summary.contains("| [KIP-1: good](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/1) | accepted | 4 | none |"), summary);
        assertTrue(summary.contains("Number checks used the space index."));
        assertTrue(Files.exists(out.resolve("proposals.json")));
        assertTrue(out().contains("wrote "));
        assertEquals(3, HashChainAudit.payloads(audit).size());

        assertEquals(0, run(wiki(kip1), "scan", "--template", "kip", "--space", "KAFKA", "--last", "1", "--out", out.toString(), "--quiet"));
        assertTrue(Files.readString(out.resolve("SUMMARY.md")).contains("Number checks were off (no --index)."));

        Path md = dir.resolve("p.md");
        Files.writeString(md, "# DIP-1 | pipe\n## Motivation\nnothing\n", StandardCharsets.UTF_8);
        Path tpl = dir.resolve("t.yaml");
        Files.writeString(tpl, "name: dip\nstate_source: none\nrequired_sections:\n  - {id: motivation, heading: \"^motivation\", min_words: 1}\n",
                StandardCharsets.UTF_8);
        assertEquals(0, run(url -> new Sources.Response(500, ""), "check", "--template-file", tpl.toString(), "--file", md.toString()));
        assertTrue(out().contains("=== DIP-1 | pipe ===\n"), out());
        assertTrue(out().contains("state unknown"));

        assertEquals(0, run(url -> new Sources.Response(200, ProposalFixtures.SPIP_JIRA), "check", "--template", "spip",
                "--jira", "SPARK-1", "--jira-base", "https://issues.apache.org/jira/"));
        assertTrue(out().contains("proposal.external-doc"));
        assertEquals("KAFKA", ProposalCli.spaceOf("https://c/display/KAFKA/KIP-1"));
        assertEquals(null, ProposalCli.spaceOf("https://c/x"));
    }

    @Test
    void renderShowsStateAndFindings() {
        ProposalDocument d = new ProposalDocument("file", "f", "T|x", "u", "1", "", List.of(), List.of());
        ProposalChecker.Result r = new ProposalChecker.Result(d, null, false, null, "s",
                List.of(io.github.vaquarkhan.aiv.model.Finding.atLine("proposal.x", "u", 1, "m")));
        assertEquals("=== T|x ===\nu (version 1, state unknown)\n  proposal.x: m\n", ProposalCli.render(r));
        String s = ProposalCli.summary(List.of(r, new ProposalChecker.Result(d, "draft", false, null, "s", List.of())),
                ProposalTemplate.parse("name: n"), "S", false);
        assertTrue(s.contains("| [T\\|x](u) | unknown | 1 | proposal.x |"), s);
        assertTrue(s.contains("| [T\\|x](u) | draft | 1 | none |"), s);
    }
}
