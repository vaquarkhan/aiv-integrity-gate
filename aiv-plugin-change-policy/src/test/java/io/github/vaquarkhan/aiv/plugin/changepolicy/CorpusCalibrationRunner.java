/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the real {@link ChangePolicyGate} over stored corpus PRs for calibration. Not a unit test: invoked
 * by {@code benchmarks/airflow/scripts/run-change-policy-calibration.py}, which prepares the input.
 *
 * <p>Input TSV columns: id, pack, base64 title, base64 body (empty when absent), base64 unified diff,
 * base64 file list ({@code status\tpath\told_path} per line), total additions, total deletions (the
 * numstat totals behind {@code lines.total_changed}). Output TSV: id, gate outcome, fired policy ids,
 * skipped policy ids, facts JSON.
 */
public final class CorpusCalibrationRunner {

    private CorpusCalibrationRunner() {
    }

    public static void main(String[] args) throws Exception {
        Base64.Decoder b64 = Base64.getDecoder();
        Map<String, AIVConfig> configs = new HashMap<>();
        // No checkout is available, so git history facts are off; PR metadata comes from the corpus.
        ChangePolicyGate gate = new ChangePolicyGate(k -> null, (ws, a) -> Optional.empty());
        try (BufferedReader in = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] c = line.split("\t", -1);
                String title = new String(b64.decode(c[2]), StandardCharsets.UTF_8);
                String body = c[3].isEmpty() ? "" : new String(b64.decode(c[3]), StandardCharsets.UTF_8);
                String raw = new String(b64.decode(c[4]), StandardCharsets.UTF_8);
                int additions = c.length > 6 ? Integer.parseInt(c[6]) : 0;
                int deletions = c.length > 7 ? Integer.parseInt(c[7]) : 0;
                List<ChangedFile> files = new ArrayList<>();
                for (String f : new String(b64.decode(c[5]), StandardCharsets.UTF_8).split("\n")) {
                    String[] p = f.split("\t", -1);
                    if (p.length >= 2) {
                        files.add(new ChangedFile(p[1], type(p[0]), "", p.length > 2 && !p[2].isEmpty() ? p[2] : null,
                                null, null, null, null, null, null, null));
                    }
                }
                AIVConfig config = configs.computeIfAbsent(c[1], pack -> new AIVConfig(List.of(new AIVConfig.GateConfig(
                        ChangePolicyGate.ID, true, Map.of("pack", pack, "git_facts", false, "emit_facts", true,
                        // The corpus has no author association, so template-compliance (maintainers exempt) is
                        // skipped; this calibration-only variant measures the same text rule for every author.
                        "policies", List.of(Map.of("id", "calibration-template-any-author", "action", "warn",
                                "when", Map.of("any", List.of(Map.of("fact", "template.sections_missing", "gt", 0),
                                        Map.of("fact", "meta.body_words", "lt", 15)))))))), Map.of()));
                Map<String, Object> meta = new HashMap<>();
                meta.put("pr_title", title);
                meta.put("pr_body", body.isEmpty() ? " " : body);
                AIVContext ctx = new AIVContext(Path.of("."), new Diff("base", "head", files, raw, additions, deletions, null, false),
                        config, meta);
                GateResult r = gate.evaluate(ctx);
                List<String> fired = new ArrayList<>();
                String facts = "{}";
                for (Finding f : r.getFindings()) {
                    if (f.getRuleId().startsWith("policy.")) {
                        fired.add(f.getRuleId().substring(7));
                    } else if (f.getRuleId().equals(ChangePolicyGate.FACTS_RULE)) {
                        facts = f.getMessage();
                    }
                }
                String skipped = r.getMessage().contains("skipped, facts unavailable: ")
                        ? r.getMessage().substring(r.getMessage().indexOf("unavailable: ") + 13) : "";
                out.write(c[0] + "\t" + (r.isPassed() ? "pass" : r.blocksCi() ? "fail" : "advisory") + "\t"
                        + String.join(",", fired) + "\t" + skipped.replace('\t', ' ') + "\t" + facts + "\n");
            }
        }
    }

    private static ChangedFile.ChangeType type(String status) {
        return switch (status) {
            case "added" -> ChangedFile.ChangeType.ADDED;
            case "removed" -> ChangedFile.ChangeType.DELETED;
            case "renamed" -> ChangedFile.ChangeType.RENAMED;
            case "copied" -> ChangedFile.ChangeType.COPIED;
            default -> ChangedFile.ChangeType.MODIFIED;
        };
    }
}
