/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.reviewrouting;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.util.PathFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Computes a deterministic review tier for the change, so AI review automation can decide whether to
 * ask a model at all and at what effort. The gate never fails and never blocks: it always passes and
 * reports exactly one {@code review-routing.tier} finding whose message starts with {@code tier=<name>}.
 *
 * <p>Default off. Enable explicitly:
 *
 * <pre>
 * - id: review-routing
 *   enabled: true
 *   config:
 *     default_tier: lite
 *     tiers:                       # evaluated in order, first match wins
 *       - tier: none               # no AI review: security-sensitive or CI definitions
 *         paths: [".github/workflows/**", "SECURITY.md", "**&#47;security/**"]
 *       - tier: balanced
 *         min_lines_changed: 400
 *       - tier: balanced
 *         min_files_changed: 20
 * </pre>
 *
 * A rule matches when every condition it lists holds. {@code paths} uses the same glob semantics as
 * {@code exclude_paths} and matches when any changed path (including paths that {@code exclude_paths}
 * removed, and the old path of a rename) matches. Tier names are lower-case letters, digits and hyphens.
 *
 * @author Vaquar Khan
 */
public final class ReviewRoutingGate implements QualityGate {

    public static final String ID = "review-routing";
    public static final String RULE_ID = "review-routing.tier";
    private static final Pattern TIER_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,29}");

    record Rule(String tier, List<String> paths, int minLines, int minFiles) {
        boolean hasCondition() {
            return !paths.isEmpty() || minLines > 0 || minFiles > 0;
        }
    }

    record Settings(String defaultTier, List<Rule> rules) {
        static Settings from(Map<String, Object> cfg) {
            String def = tierName(cfg.getOrDefault("default_tier", "lite"), "default_tier");
            List<Rule> rules = new ArrayList<>();
            Object raw = cfg.get("tiers");
            if (raw != null && !(raw instanceof List<?>)) {
                throw new IllegalArgumentException("review-routing.tiers must be a list");
            }
            int i = 0;
            for (Object o : raw instanceof List<?> l ? l : List.of()) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("review-routing.tiers[" + i + "] must be a map");
                }
                Rule r = new Rule(tierName(m.get("tier"), "tiers[" + i + "].tier"),
                        strings(m.get("paths")), nonNegative(m.get("min_lines_changed"), i),
                        nonNegative(m.get("min_files_changed"), i));
                if (!r.hasCondition()) {
                    throw new IllegalArgumentException("review-routing.tiers[" + i
                            + "] needs paths, min_lines_changed or min_files_changed");
                }
                rules.add(r);
                i++;
            }
            return new Settings(def, rules);
        }
    }

    static String tierName(Object v, String field) {
        String s = v == null ? "" : v.toString().trim();
        if (!TIER_NAME.matcher(s).matches()) {
            throw new IllegalArgumentException("review-routing." + field
                    + " must be lower-case letters, digits or hyphens (max 30), got '" + s + "'");
        }
        return s;
    }

    private static int nonNegative(Object v, int index) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n && n.intValue() >= 0 && n.doubleValue() == n.intValue()) {
            return n.intValue();
        }
        throw new IllegalArgumentException("review-routing.tiers[" + index + "] thresholds must be non-negative integers");
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
        } else if (v != null) {
            throw new IllegalArgumentException("review-routing paths must be a list");
        }
        return out;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Settings s = Settings.from(context.getConfig().findGate(ID).map(g -> g.getConfig()).orElse(Map.of()));
        Set<String> paths = allPaths(context);
        Diff diff = context.getDiff();
        int lines = Math.max(0, diff.getLinesAdded()) + Math.max(0, diff.getLinesDeleted());
        int files = paths.size();

        String tier = s.defaultTier();
        String reason = "default tier";
        for (int i = 0; i < s.rules().size(); i++) {
            Rule r = s.rules().get(i);
            String matchedPath = r.paths().isEmpty() ? null : firstMatch(paths, r.paths());
            boolean pathsOk = r.paths().isEmpty() || matchedPath != null;
            boolean linesOk = r.minLines() == 0 || lines >= r.minLines();
            boolean filesOk = r.minFiles() == 0 || files >= r.minFiles();
            if (pathsOk && linesOk && filesOk) {
                tier = r.tier();
                List<String> why = new ArrayList<>();
                if (matchedPath != null) {
                    why.add("path " + matchedPath);
                }
                if (r.minLines() > 0) {
                    why.add(lines + " lines changed >= " + r.minLines());
                }
                if (r.minFiles() > 0) {
                    why.add(files + " files changed >= " + r.minFiles());
                }
                reason = "rule " + i + ": " + String.join(", ", why);
                break;
            }
        }
        String msg = "tier=" + tier + "; " + reason + "; lines=" + lines + "; files=" + files;
        return new GateResult(ID, true, msg, false, List.of(Finding.atLine(RULE_ID, "(diff)", 1, msg)));
    }

    private static Set<String> allPaths(AIVContext context) {
        Set<String> paths = new LinkedHashSet<>();
        List<ChangedFile> files = new ArrayList<>(context.getDiff().getChangedFiles());
        if (context.getMetadata().get(AIVContext.EXCLUDED_CHANGED_FILES) instanceof List<?> excluded) {
            for (Object o : excluded) {
                if (o instanceof ChangedFile f) {
                    files.add(f);
                }
            }
        }
        for (ChangedFile f : files) {
            add(paths, f.getPath());
            add(paths, f.getOldPath());
        }
        return paths;
    }

    private static void add(Set<String> paths, String p) {
        if (p != null && !p.isBlank()) {
            paths.add(p.replace('\\', '/'));
        }
    }

    private static String firstMatch(Set<String> paths, List<String> patterns) {
        for (String p : paths) {
            if (PathFilter.isExcluded(p, patterns)) {
                return p;
            }
        }
        return null;
    }

    /** Extracts the tier from a {@code review-routing.tier} finding message; empty when absent. */
    public static String tierFromMessage(String message) {
        if (message == null || !message.startsWith("tier=")) {
            return "";
        }
        int end = message.indexOf(';');
        return (end < 0 ? message.substring(5) : message.substring(5, end)).trim();
    }
}
