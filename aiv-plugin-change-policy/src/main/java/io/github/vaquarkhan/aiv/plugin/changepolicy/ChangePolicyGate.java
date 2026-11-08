/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates declarative change policies (review budgets, untested source changes, title versus diff,
 * description claims, template and history rules) over deterministic facts about the pull request.
 *
 * <p>Default off. Every built-in policy is advisory ({@code warn}); a project promotes a measured policy
 * with {@code action: fail}. A policy whose facts are unavailable (for example PR metadata in a local run)
 * does not fire and is listed as skipped. Maintainer override labels turn a fired policy into a notice.
 *
 * <pre>
 * - id: change-policy
 *   enabled: true
 *   config:
 *     pack: oss-maintainer          # or airflow, strict-agent, none
 *     budgets: { external.core: { non_test_lines: 200 } }
 *     overrides: { labels: [large-change-approved] }
 *     policies:
 *       - { id: untested-source-change, action: fail }
 * </pre>
 *
 * See docs/AI-integration/CHANGE-POLICY-ENGINE-DESIGN.md.
 *
 * @author Vaquar Khan
 */
public final class ChangePolicyGate implements QualityGate {

    public static final String ID = "change-policy";
    public static final String FACTS_RULE = "change-policy.facts";
    public static final String OVERRIDE_RULE = "change-policy.override";
    private static final Pattern JSON_STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Function<String, String> env;
    private final GitHistory git;

    public ChangePolicyGate() {
        this(System::getenv, null);
    }

    ChangePolicyGate(Function<String, String> env, GitHistory git) {
        this.env = env;
        this.git = git;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        PolicyConfig cfg = PolicyConfig.load(context.getConfig().findGate(ID).map(g -> g.getConfig()).orElse(Map.of()));
        FactCollector.Meta meta = meta(context);
        GitHistory history = git != null ? git : GitHistory.process(cfg.gitTimeoutMs());
        Map<String, Object> facts = FactCollector.collect(context.getDiff(), allFiles(context), meta, cfg, history,
                context.getWorkspace());
        List<Finding> findings = new ArrayList<>();
        Set<String> skipped = new TreeSet<>();
        Set<String> labels = new LinkedHashSet<>(meta.labels() == null ? List.of() : meta.labels());
        int blocking = 0, advisory = 0;
        for (PolicyConfig.Policy p : cfg.policies()) {
            String rule = "policy." + p.id();
            String action = context.getConfig().getRuleAction(ID, rule, p.action());
            if (action.equals("off")) {
                continue;
            }
            Set<String> unknown = new TreeSet<>();
            Condition.Tri t = p.when().eval(facts, unknown);
            if (t == Condition.Tri.UNKNOWN) {
                skipped.add(p.id() + " (" + String.join(", ", unknown) + ")");
                continue;
            }
            if (t == Condition.Tri.FALSE) {
                continue;
            }
            // Policy-specific labels always apply; project-wide labels only for overridable policies.
            String override = firstLabel(labels, p.overrideLabels(), p.overridable() ? cfg.overrideLabels() : List.of());
            if (override != null) {
                findings.add(Finding.atLine(OVERRIDE_RULE, "(diff)", 1,
                        "[POLICY-OVERRIDE] " + p.id() + " by label " + override));
                continue;
            }
            findings.add(Finding.atLine(rule, "(diff)", 1, "[" + action.toUpperCase() + "] "
                    + Facts.render(p.message(), facts, cfg.params())));
            if (action.equals("fail")) {
                blocking++;
            } else {
                advisory++;
            }
        }
        if (cfg.emitFacts()) {
            findings.add(Finding.atLine(FACTS_RULE, "(diff)", 1, Facts.toJson(facts)));
        }
        String msg = (blocking + advisory) + " change policies matched (" + blocking + " blocking)"
                + (skipped.isEmpty() ? "" : "; skipped, facts unavailable: " + String.join("; ", skipped));
        if (blocking > 0) {
            // Only fail-action policies block; warn policies, overrides and the facts note stay advisory.
            Set<String> advisoryRules = new LinkedHashSet<>();
            for (Finding f : findings) {
                if (!f.getMessage().startsWith("[FAIL] ")) {
                    advisoryRules.add(f.getRuleId());
                }
            }
            return new GateResult(ID, false, msg, true, findings, advisoryRules);
        }
        return advisory > 0 ? GateResult.advisory(ID, msg, findings) : new GateResult(ID, true, msg, false, findings);
    }

    private static String firstLabel(Set<String> present, List<String> perPolicy, List<String> global) {
        for (List<String> source : List.of(perPolicy, global)) {
            for (String l : source) {
                if (present.contains(l)) {
                    return l;
                }
            }
        }
        return null;
    }

    private static List<ChangedFile> allFiles(AIVContext context) {
        List<ChangedFile> files = new ArrayList<>(context.getDiff().getChangedFiles());
        if (context.getMetadata().get(AIVContext.EXCLUDED_CHANGED_FILES) instanceof List<?> excluded) {
            for (Object o : excluded) {
                if (o instanceof ChangedFile f) {
                    files.add(f);
                }
            }
        }
        return files;
    }

    FactCollector.Meta meta(AIVContext context) {
        String title = text(context, "pr_title", "AIV_PR_TITLE");
        String body = text(context, "pr_body", "AIV_PR_BODY");
        String assoc = text(context, "pr_author_association", "AIV_PR_AUTHOR_ASSOCIATION");
        String draft = text(context, "pr_draft", "AIV_PR_DRAFT");
        Object rawLabels = context.getMetadata().get("pr_labels");
        List<String> labels;
        if (rawLabels instanceof List<?> l) {
            labels = l.stream().map(String::valueOf).toList();
        } else {
            String json = rawLabels != null ? rawLabels.toString() : env.apply("AIV_PR_LABELS");
            labels = json == null || json.isBlank() ? null : jsonStrings(json);
        }
        // In a PR run the title is always present, so a blank body is an empty description, not unknown.
        return new FactCollector.Meta(title, body == null && title != null ? "" : body, labels, assoc == null ? null : assoc.trim(),
                draft == null ? null : Boolean.parseBoolean(draft.trim()));
    }

    /** Metadata key first (set by embedding tools), then the variable the composite action exports. */
    private String text(AIVContext context, String key, String envVar) {
        Object v = context.getMetadata().get(key);
        String s = v != null && !v.toString().isBlank() ? v.toString() : env.apply(envVar);
        return s == null || s.isBlank() ? null : s;
    }

    static List<String> jsonStrings(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = JSON_STRING.matcher(json);
        while (m.find()) {
            out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return out;
    }
}
