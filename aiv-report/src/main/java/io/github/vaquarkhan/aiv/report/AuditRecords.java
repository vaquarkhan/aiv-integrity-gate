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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical audit records. A record is a JSON object with a {@code schema} field; the chain stores it as
 * the payload of one hash-chained line. Two schemas exist: {@code aiv-gate-run/1} (one AIV run on a diff)
 * and {@code aiv-proposal/1} (one proposal check, written by the proposal module).
 *
 * <p>Override evidence is derived from the run's notices: {@code [CONFIG-CHANGE]} (the PR edited
 * {@code .aiv/}), {@code [SKIP-IGNORED]} and {@code [SKIP-HONORED]} ({@code /aiv skip} attempts) and
 * {@code [SUPPRESSED]} (findings removed by a baseline or inline directive). They are recorded as their own
 * field so a reader of the chain can answer "who switched what off, and when" without parsing prose.
 */
public final class AuditRecords {

    public static final String GATE_RUN = "aiv-gate-run/1";

    private AuditRecords() {
    }

    /** Builds the canonical record for one gate run. {@code meta} keys are copied verbatim (ts, refs, shas, ...). */
    public static Map<String, Object> gateRun(AIVResult result, Map<String, Object> meta) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("schema", GATE_RUN);
        rec.putAll(meta);
        rec.put("passed", result.isPassed());
        List<Object> gates = new ArrayList<>();
        for (GateResult g : result.getGateResults()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getGateId());
            gm.put("passed", g.isPassed());
            gm.put("blocks_ci", g.blocksCi());
            List<Object> fs = new ArrayList<>();
            for (Finding f : g.getFindings()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("rule_id", f.getRuleId());
                fm.put("file", f.getFilePath());
                fm.put("line", f.getStartLine());
                fs.add(fm);
            }
            gm.put("findings", fs);
            gates.add(gm);
        }
        rec.put("gates", gates);
        rec.put("notices", result.getNotices());
        rec.put("overrides", overrides(result.getNotices()));
        return rec;
    }

    static Map<String, Object> overrides(List<String> notices) {
        Map<String, Object> o = new LinkedHashMap<>();
        List<String> suppressed = new ArrayList<>();
        boolean configChange = false;
        boolean skipIgnored = false;
        boolean skipHonored = false;
        for (String n : notices) {
            if (n.startsWith("[CONFIG-CHANGE]")) {
                configChange = true;
            } else if (n.startsWith("[SKIP-IGNORED]")) {
                skipIgnored = true;
            } else if (n.startsWith("[SKIP-HONORED]")) {
                skipHonored = true;
            } else if (n.startsWith("[SUPPRESSED]")) {
                suppressed.add(n.substring("[SUPPRESSED]".length()).trim());
            }
        }
        o.put("config_change", configChange);
        o.put("skip_ignored", skipIgnored);
        o.put("skip_honored", skipHonored);
        o.put("suppressed", suppressed);
        return o;
    }

    /** Appends a record to the chain; returns {@code [recordNumber, headHash]} (1-based record number). */
    public static String[] append(Path log, Map<String, Object> record) throws IOException {
        String head = HashChainAudit.append(log, JsonLite.write(record));
        return new String[] {String.valueOf(HashChainAudit.verify(log).getRecordCount()), head};
    }

    /** The notice a run prints and carries into its JSON report and PR comment: the externally visible anchor. */
    public static String anchorNotice(String recordNumber, Path log, String head) {
        return "[AUDIT] record #" + recordNumber + " appended to " + log.toString().replace('\\', '/')
                + "; chain head " + head;
    }
}
