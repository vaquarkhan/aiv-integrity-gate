/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.report;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.audit.VerifyResult;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Aggregates a hash-chained audit log into the numbers a maintainer needs before trusting a gate:
 * chain integrity first, then per-rule counts and, when reviewers have labelled findings, a calibration
 * verdict per rule (ready to promote from advisory to blocking, keep advisory, needs labels).
 *
 * <p>The chain is verified before anything is counted. A broken chain still produces a report, but the
 * banner says where it breaks and every number below is marked as coming from an unverified log.
 */
public final class AuditReport {

    /** Reviewer verdict on one finding, keyed by rule, file and line. */
    public record Label(String ruleId, String file, int line, boolean truePositive) {
    }

    /** Per-rule aggregate. */
    public static final class RuleStats {
        final String ruleId;
        int findings;
        final TreeSet<Integer> runs = new TreeSet<>();
        final TreeSet<String> files = new TreeSet<>();
        int blocking;
        int advisory;
        int tp;
        int fp;
        String lastSeen = "";

        RuleStats(String ruleId) {
            this.ruleId = ruleId;
        }

        public String ruleId() {
            return ruleId;
        }

        public int findings() {
            return findings;
        }

        public int runs() {
            return runs.size();
        }

        public int truePositives() {
            return tp;
        }

        public int falsePositives() {
            return fp;
        }

        public int blocking() {
            return blocking;
        }

        public int advisory() {
            return advisory;
        }

        public int distinctFiles() {
            return files.size();
        }

        public String lastSeen() {
            return lastSeen;
        }

        /** Calibration verdict, see {@link AuditReport#verdict(RuleStats, int)}. */
        public String verdict(int minLabeled) {
            return AuditReport.verdict(this, minLabeled);
        }
    }

    final VerifyResult chain;
    final String head;
    final List<Map<String, Object>> records;
    final int gateRuns;
    final int passedRuns;
    final Map<String, int[]> gates = new TreeMap<>();
    final Map<String, RuleStats> rules = new TreeMap<>();
    final Map<String, Integer> overrides = new LinkedHashMap<>();
    final List<Map<String, Object>> proposals = new ArrayList<>();
    final String firstTs;
    final String lastTs;

    private AuditReport(VerifyResult chain, String head, List<Map<String, Object>> records, List<Label> labels) {
        this.chain = chain;
        this.head = head;
        this.records = records;
        overrides.put("config_change", 0);
        overrides.put("skip_ignored", 0);
        overrides.put("skip_honored", 0);
        overrides.put("suppressed", 0);
        Map<String, Boolean> labelIndex = new HashMap<>();
        for (Label l : labels) {
            labelIndex.put(key(l.ruleId(), l.file(), l.line()), l.truePositive());
        }
        int runs = 0;
        int passed = 0;
        String first = null;
        String last = null;
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> r = records.get(i);
            String ts = JsonLite.str(r.get("ts"));
            if (ts != null) {
                first = first == null ? ts : first;
                last = ts;
            }
            String schema = JsonLite.str(r.get("schema"));
            if ("aiv-proposal/1".equals(schema)) {
                proposals.add(r);
                continue;
            }
            if (!AuditRecords.GATE_RUN.equals(schema)) {
                continue;
            }
            runs++;
            if (Boolean.TRUE.equals(r.get("passed"))) {
                passed++;
            }
            countOverrides(r.get("overrides"));
            for (Object go : JsonLite.list(r.get("gates"))) {
                String gid = JsonLite.str(JsonLite.get(go, "id"));
                int[] g = gates.computeIfAbsent(gid, k -> new int[3]);
                g[0]++;
                boolean gatePassed = Boolean.TRUE.equals(JsonLite.get(go, "passed"));
                boolean blocks = Boolean.TRUE.equals(JsonLite.get(go, "blocks_ci"));
                if (!gatePassed) {
                    g[blocks ? 1 : 2]++;
                }
                for (Object fo : JsonLite.list(JsonLite.get(go, "findings"))) {
                    String rule = JsonLite.str(JsonLite.get(fo, "rule_id"));
                    String file = JsonLite.str(JsonLite.get(fo, "file"));
                    int line = JsonLite.get(fo, "line") instanceof Number n ? n.intValue() : 0;
                    RuleStats s = rules.computeIfAbsent(rule, RuleStats::new);
                    s.findings++;
                    s.runs.add(i);
                    s.files.add(file);
                    if (blocks) {
                        s.blocking++;
                    } else {
                        s.advisory++;
                    }
                    if (ts != null) {
                        s.lastSeen = ts;
                    }
                    Boolean verdict = labelIndex.get(key(rule, file, line));
                    if (verdict != null) {
                        if (verdict) {
                            s.tp++;
                        } else {
                            s.fp++;
                        }
                    }
                }
            }
        }
        this.gateRuns = runs;
        this.passedRuns = passed;
        this.firstTs = first == null ? "" : first;
        this.lastTs = last == null ? "" : last;
    }

    private void countOverrides(Object o) {
        if (Boolean.TRUE.equals(JsonLite.get(o, "config_change"))) {
            overrides.merge("config_change", 1, Integer::sum);
        }
        if (Boolean.TRUE.equals(JsonLite.get(o, "skip_ignored"))) {
            overrides.merge("skip_ignored", 1, Integer::sum);
        }
        if (Boolean.TRUE.equals(JsonLite.get(o, "skip_honored"))) {
            overrides.merge("skip_honored", 1, Integer::sum);
        }
        overrides.merge("suppressed", JsonLite.list(JsonLite.get(o, "suppressed")).size(), Integer::sum);
    }

    private static String key(String rule, String file, int line) {
        return rule + "|" + (file == null ? "" : file.replace('\\', '/')) + "|" + line;
    }

    /**
     * Calibration verdict. A rule is ready to promote only when at least {@code minLabeled} of its findings
     * were labelled by a reviewer and none of them was a false positive. A blocking rule with any false
     * positive is flagged for review. Rules without enough labels say so instead of guessing.
     */
    static String verdict(RuleStats s, int minLabeled) {
        int labeled = s.tp + s.fp;
        if (s.fp > 0) {
            return s.blocking > 0 ? "review: false positives on a blocking rule" : "keep advisory";
        }
        if (labeled < minLabeled) {
            return "needs labels (" + labeled + "/" + minLabeled + ")";
        }
        return s.blocking > 0 && s.advisory == 0 ? "blocking, calibrated" : "ready to promote";
    }

    /** Verifies the chain (optionally against an external anchor), decodes every record and aggregates. */
    @SuppressWarnings("unchecked")
    public static AuditReport load(Path log, String expectedHead, List<Label> labels) throws IOException {
        VerifyResult v = HashChainAudit.verify(log, expectedHead);
        List<Map<String, Object>> recs = new ArrayList<>();
        List<String> payloads = HashChainAudit.payloads(log);
        for (String p : payloads) {
            Object o;
            try {
                o = JsonLite.parse(p);
            } catch (IllegalArgumentException e) {
                o = Map.of("schema", "unparseable");
            }
            recs.add(o instanceof Map ? (Map<String, Object>) o : Map.of("schema", "unparseable"));
        }
        return new AuditReport(v, HashChainAudit.headHash(log), recs, labels == null ? List.of() : labels);
    }

    /** Reads {@code rule_id,file,line,verdict} rows; verdict is {@code tp} or {@code fp}; {@code #} lines and a header are skipped. */
    public static List<Label> readLabels(Path csv) throws IOException {
        List<Label> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("#") || t.toLowerCase(Locale.ROOT).startsWith("rule_id,")) {
                continue;
            }
            String[] p = t.split(",", -1);
            if (p.length != 4) {
                throw new IllegalArgumentException("labels line " + (i + 1) + ": expected rule_id,file,line,verdict");
            }
            String v = p[3].trim().toLowerCase(Locale.ROOT);
            if (!v.equals("tp") && !v.equals("fp")) {
                throw new IllegalArgumentException("labels line " + (i + 1) + ": verdict must be tp or fp");
            }
            int line;
            try {
                line = Integer.parseInt(p[2].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("labels line " + (i + 1) + ": line must be an integer");
            }
            out.add(new Label(p[0].trim(), p[1].trim().replace('\\', '/'), line, v.equals("tp")));
        }
        return out;
    }

    public boolean chainIntact() {
        return chain.isIntact();
    }

    public int recordCount() {
        return records.size();
    }

    public int gateRuns() {
        return gateRuns;
    }

    public int passedRuns() {
        return passedRuns;
    }

    public Map<String, RuleStats> rules() {
        return rules;
    }

    public Map<String, Integer> overrides() {
        return overrides;
    }

    public List<Map<String, Object>> proposals() {
        return proposals;
    }

    /** One-line chain status used in the CLI and the HTML banner. */
    public String chainStatus() {
        if (chain.isIntact()) {
            return "chain intact: " + chain.getRecordCount() + " records, head " + head;
        }
        if (chain.isHeadMismatch()) {
            return "chain head does not match the external anchor: the log was rewritten or truncated";
        }
        return "chain BROKEN at record index " + chain.getFirstBrokenIndex() + " of " + chain.getRecordCount()
                + ": numbers below come from an unverified log";
    }

    /**
     * Writes {@code records.jsonl} (one decoded record per line) and {@code findings.jsonl} (one row per
     * finding: record index, ts, schema, gate, rule_id, file, line, blocks_ci) for SQL engines such as DuckDB
     * ({@code select * from read_json_auto('findings.jsonl')}).
     */
    public void exportJsonl(Path dir) throws IOException {
        Files.createDirectories(dir);
        StringBuilder recs = new StringBuilder();
        StringBuilder finds = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> r = records.get(i);
            recs.append(JsonLite.write(r)).append('\n');
            for (Object go : JsonLite.list(r.get("gates"))) {
                for (Object fo : JsonLite.list(JsonLite.get(go, "findings"))) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("record", i);
                    row.put("ts", r.get("ts"));
                    row.put("head_sha", r.get("head_sha"));
                    row.put("gate", JsonLite.get(go, "id"));
                    row.put("rule_id", JsonLite.get(fo, "rule_id"));
                    row.put("file", JsonLite.get(fo, "file"));
                    row.put("line", JsonLite.get(fo, "line"));
                    row.put("blocks_ci", JsonLite.get(go, "blocks_ci"));
                    finds.append(JsonLite.write(row)).append('\n');
                }
            }
        }
        Files.writeString(dir.resolve("records.jsonl"), recs.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("findings.jsonl"), finds.toString(), StandardCharsets.UTF_8);
    }

    /** Self-contained static HTML (inline CSS, no script, no external requests). */
    public String html(int minLabeled) {
        StringBuilder h = new StringBuilder(8192);
        h.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>AIV evidence report</title>\n")
                .append("<style>body{font-family:system-ui,sans-serif;margin:2rem;color:#14213d;max-width:1100px}")
                .append("table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid #c9d1d9;padding:.4rem .6rem;")
                .append("text-align:left;vertical-align:top}th{background:#eef2f7}caption{text-align:left;font-weight:600;padding:.4rem 0}")
                .append(".ok{background:#e8f5ee;border-left:6px solid #1b7f4b;padding:.8rem}")
                .append(".bad{background:#fdecec;border-left:6px solid #b42318;padding:.8rem}code{font-size:.9em}</style></head><body>\n")
                .append("<h1>AIV evidence report</h1>\n")
                .append("<p class=\"").append(chain.isIntact() ? "ok" : "bad").append("\" role=\"status\">")
                .append(esc(chainStatus())).append("</p>\n")
                .append("<p>Records: ").append(records.size()).append(". Gate runs: ").append(gateRuns)
                .append(" (passed ").append(passedRuns).append("). Proposal checks: ").append(proposals.size())
                .append(". Period: ").append(esc(firstTs)).append(" to ").append(esc(lastTs)).append(".</p>\n")
                .append("<p>Findings are deterministic evidence. They do not determine whether AI authored a change.</p>\n");

        h.append("<table><caption>Rules: counts and calibration (minimum ").append(minLabeled)
                .append(" labelled findings and zero false positives to promote)</caption>\n")
                .append("<tr><th scope=\"col\">Rule</th><th scope=\"col\">Findings</th><th scope=\"col\">Runs</th>")
                .append("<th scope=\"col\">Files</th><th scope=\"col\">In blocking / advisory gate results</th><th scope=\"col\">TP / FP</th>")
                .append("<th scope=\"col\">Calibration</th><th scope=\"col\">Last seen</th></tr>\n");
        for (RuleStats s : rules.values()) {
            h.append("<tr><td><code>").append(esc(s.ruleId)).append("</code></td><td>").append(s.findings)
                    .append("</td><td>").append(s.runs.size()).append("</td><td>").append(s.files.size())
                    .append("</td><td>").append(s.blocking).append(" / ").append(s.advisory)
                    .append("</td><td>").append(s.tp).append(" / ").append(s.fp)
                    .append("</td><td>").append(esc(verdict(s, minLabeled))).append("</td><td>")
                    .append(esc(s.lastSeen)).append("</td></tr>\n");
        }
        if (rules.isEmpty()) {
            h.append("<tr><td colspan=\"8\">No findings recorded.</td></tr>\n");
        }
        h.append("</table>\n");

        h.append("<table><caption>Gates</caption>\n<tr><th scope=\"col\">Gate</th><th scope=\"col\">Runs</th>")
                .append("<th scope=\"col\">Blocking failures</th><th scope=\"col\">Advisory failures</th></tr>\n");
        for (Map.Entry<String, int[]> e : gates.entrySet()) {
            h.append("<tr><td><code>").append(esc(e.getKey())).append("</code></td><td>").append(e.getValue()[0])
                    .append("</td><td>").append(e.getValue()[1]).append("</td><td>").append(e.getValue()[2]).append("</td></tr>\n");
        }
        h.append("</table>\n");

        h.append("<table><caption>Override evidence</caption>\n<tr><th scope=\"col\">Kind</th><th scope=\"col\">Count</th>")
                .append("<th scope=\"col\">Meaning</th></tr>\n");
        Map<String, String> meaning = Map.of(
                "config_change", "runs where the change edited .aiv/ (judged with the base-ref config)",
                "skip_ignored", "/aiv skip attempts that were refused (not allowlisted or not signed)",
                "skip_honored", "/aiv skip honoured for an allowlisted, signed author (no gate ran)",
                "suppressed", "gate results where a baseline or inline aiv-disable removed findings");
        for (Map.Entry<String, Integer> e : overrides.entrySet()) {
            h.append("<tr><td><code>").append(e.getKey()).append("</code></td><td>").append(e.getValue())
                    .append("</td><td>").append(esc(meaning.get(e.getKey()))).append("</td></tr>\n");
        }
        h.append("</table>\n");

        if (!proposals.isEmpty()) {
            h.append("<table><caption>Proposal checks</caption>\n<tr><th scope=\"col\">Proposal</th>")
                    .append("<th scope=\"col\">State</th><th scope=\"col\">Version</th><th scope=\"col\">Findings</th>")
                    .append("<th scope=\"col\">Content SHA-256</th></tr>\n");
            for (Map<String, Object> p : proposals) {
                h.append("<tr><td>").append(esc(JsonLite.str(p.get("title")))).append("</td><td>")
                        .append(esc(JsonLite.str(p.get("state")))).append("</td><td>")
                        .append(esc(JsonLite.str(p.get("version")))).append("</td><td>")
                        .append(esc(String.join(", ", strings(p.get("rules"))))).append("</td><td><code>")
                        .append(esc(JsonLite.str(p.get("content_sha256")))).append("</code></td></tr>\n");
            }
            h.append("</table>\n");
        }
        h.append("<p>Generated by AIV from a hash-chained audit log. Reproduce with <code>aiv report --audit-log &lt;log&gt;</code>.</p>\n")
                .append("</body></html>\n");
        return h.toString();
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : JsonLite.list(v)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
