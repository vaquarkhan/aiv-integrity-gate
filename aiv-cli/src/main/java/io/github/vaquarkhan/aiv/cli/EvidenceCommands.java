/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.audit.VerifyResult;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.report.AuditRecords;
import io.github.vaquarkhan.aiv.report.AuditReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tamper-evident record and evidence reports: {@code --audit-log} on a gate run, {@code aiv audit verify} and
 * {@code aiv report}. The chain format and its guarantee live in {@code aiv-audit}; this class only decides
 * what goes into a record and where the anchor is printed.
 */
final class EvidenceCommands {

    static Clock CLOCK = Clock.systemUTC();

    private EvidenceCommands() {
    }

    /**
     * Appends one gate-run record and returns a copy of {@code result} whose notices end with the
     * {@code [AUDIT]} anchor line, so the JSON report, SARIF, GitHub Check and PR comment all carry the head hash.
     */
    static AIVResult record(Path log, AIVResult result, Path workspace, String baseRef, String headRef, int exitCode,
                            boolean doctor, String cliVersion, String configSource, String pinnedPolicyCommit) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ts", CLOCK.instant().toString());
        meta.put("cli_version", cliVersion);
        meta.put("base_ref", baseRef);
        meta.put("head_ref", headRef);
        meta.put("base_sha", pinnedPolicyCommit != null ? pinnedPolicyCommit : revParse(workspace, baseRef));
        meta.put("policy_commit", pinnedPolicyCommit);
        meta.put("head_sha", revParse(workspace, headRef));
        meta.put("author", gitAuthor(workspace));
        meta.put("config_source", configSource);
        meta.put("exit_code", exitCode);
        meta.put("doctor", doctor);
        String[] appended = AuditRecords.append(log, AuditRecords.gateRun(result, meta));
        List<String> notices = new ArrayList<>(result.getNotices());
        notices.add(AuditRecords.anchorNotice(appended[0], log, appended[1]));
        return new AIVResult(result.isPassed(), result.getGateResults(), notices);
    }

    /** {@code git rev-parse --verify <ref>^{commit}}, or null outside a repository or for an unknown ref. */
    static String revParse(Path workspace, String ref) {
        return git(workspace, "rev-parse", "--verify", "--quiet", ref + "^{commit}");
    }

    static String gitAuthor(Path workspace) {
        return git(workspace, "log", "-1", "--format=%ae", "HEAD");
    }

    static String git(Path workspace, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(workspace.toFile()).redirectErrorStream(false).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            p.getErrorStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return null;
            }
            String s = new String(out, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException | InterruptedException e) {
            // Audit facts are best effort: a missing git or an interrupted call records null, never fails the run.
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return null;
        }
    }

    /** {@code aiv audit verify <log> [--expect-head <hash>]}: 0 intact, 1 broken or anchor mismatch, 2 usage. */
    static int audit(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || !"verify".equals(args[0])) {
            err.println("Usage: aiv audit verify <log> [--expect-head <sha256>]");
            return 2;
        }
        Path log = Path.of(args[1]);
        String expected = null;
        for (int i = 2; i < args.length; i++) {
            if ("--expect-head".equals(args[i]) && i + 1 < args.length) {
                expected = args[++i];
            } else {
                err.println("Usage: aiv audit verify <log> [--expect-head <sha256>]");
                return 2;
            }
        }
        try {
            if (!Files.exists(log)) {
                err.println("audit log not found: " + log);
                return 2;
            }
            VerifyResult v = HashChainAudit.verify(log, expected);
            if (v.isIntact()) {
                out.println("intact: " + v.getRecordCount() + " records, head " + HashChainAudit.headHash(log)
                        + (expected == null ? " (no external anchor given)" : " (matches anchor)"));
                return 0;
            }
            if (v.isHeadMismatch()) {
                out.println("TAMPERED: chain is internally consistent but its head does not match the anchor " + expected);
                return 1;
            }
            out.println("TAMPERED: chain breaks at record index " + v.getFirstBrokenIndex() + " of " + v.getRecordCount());
            return 1;
        } catch (IOException e) {
            err.println("cannot read audit log: " + e.getMessage());
            return 2;
        }
    }

    /**
     * {@code aiv report --audit-log <log> [--out report.html] [--labels labels.csv] [--export-jsonl dir]
     * [--expect-head <hash>] [--min-labeled N]}. Exit 0 when the chain verifies, 1 when it does not (the report
     * is still written, with the break called out), 2 usage.
     */
    static int report(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                o.put(args[i], args[++i]);
            } else {
                err.println(REPORT_USAGE);
                return 2;
            }
        }
        if (!o.containsKey("--audit-log")) {
            err.println(REPORT_USAGE);
            return 2;
        }
        try {
            Path log = Path.of(o.get("--audit-log"));
            if (!Files.exists(log)) {
                err.println("audit log not found: " + log);
                return 2;
            }
            int minLabeled = Integer.parseInt(o.getOrDefault("--min-labeled", "20"));
            List<AuditReport.Label> labels = o.containsKey("--labels")
                    ? AuditReport.readLabels(Path.of(o.get("--labels"))) : List.of();
            AuditReport r = AuditReport.load(log, o.get("--expect-head"), labels);
            Path html = Path.of(o.getOrDefault("--out", "aiv-report.html"));
            Path parent = html.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Files.writeString(html, r.html(minLabeled), StandardCharsets.UTF_8);
            if (o.containsKey("--export-jsonl")) {
                r.exportJsonl(Path.of(o.get("--export-jsonl")));
                out.println("exported records.jsonl and findings.jsonl to " + o.get("--export-jsonl"));
            }
            out.println(r.chainStatus());
            out.println("gate runs " + r.gateRuns() + ", rules " + r.rules().size() + ", proposal checks " + r.proposals().size()
                    + "; wrote " + html.toString().replace('\\', '/'));
            return r.chainIntact() ? 0 : 1;
        } catch (NumberFormatException e) {
            err.println("--min-labeled must be an integer");
            return 2;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("aiv report: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("aiv report: " + e.getMessage());
            return 2;
        }
    }

    static final String REPORT_USAGE = "Usage: aiv report --audit-log <log> [--out report.html] [--labels labels.csv]"
            + " [--export-jsonl dir] [--expect-head <sha256>] [--min-labeled N]";
}
