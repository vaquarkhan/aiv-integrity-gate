/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated change-policy configuration: a built-in pack (default {@code oss-maintainer}) overlaid with
 * the project's gate config. Maps merge by key with the project winning; classification globs and areas
 * from the project are checked before the pack's; policies merge field by field by {@code id}.
 * Unknown keys, categories, facts, operators or params are configuration errors.
 *
 * @author Vaquar Khan
 */
record PolicyConfig(
        Classifier classifier,
        Set<String> nonReviewable,
        Map<String, Limits> budgets,
        Map<String, String> associationTier,
        List<String> requiredSections,
        List<Pattern> templateIgnore,
        List<Pattern> linkedIssuePatterns,
        List<String> overrideLabels,
        Map<String, Object> params,
        List<Policy> policies,
        boolean emitFacts,
        boolean gitFacts,
        long gitTimeoutMs) {

    static final Set<String> KEYS = Set.of("pack", "params", "classification", "non_reviewable_categories", "areas",
            "area_depth", "budgets", "author_tiers", "template", "linked_issue_patterns", "overrides", "policies",
            "emit_facts", "git_facts", "git_timeout_ms", "rule_actions", "unsuppressible_rules");
    static final Set<String> POLICY_KEYS = Set.of("id", "when", "action", "overridable", "override_labels", "message");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,49}");

    record Limits(Integer files, Integer lines, Integer nonTestLines) {
    }

    record Policy(String id, Condition when, String action, boolean overridable, List<String> overrideLabels,
                  String message) {
    }

    static PolicyConfig load(Map<String, Object> project) {
        for (String k : project.keySet()) {
            if (!KEYS.contains(k)) {
                throw err("unknown key '" + k + "'");
            }
        }
        String packName = String.valueOf(project.getOrDefault("pack", "oss-maintainer"));
        Map<String, Object> pack = packName.equals("none") ? Map.of() : pack(packName);
        Map<String, Object> params = merged(map(pack.get("params"), "params"), map(project.get("params"), "params"));
        Map<String, List<String>> classification = new LinkedHashMap<>();
        for (Map<String, Object> layer : List.of(map(project.get("classification"), "classification"),
                map(pack.get("classification"), "classification"))) {
            for (Map.Entry<String, Object> e : layer.entrySet()) {
                if (!Classifier.CATEGORIES.contains(e.getKey()) || e.getKey().equals(Classifier.SOURCE)) {
                    throw err("unknown classification category '" + e.getKey() + "'");
                }
                classification.computeIfAbsent(e.getKey(), x -> new ArrayList<>()).addAll(strings(e.getValue(), "classification"));
            }
        }
        Map<String, List<String>> areas = new LinkedHashMap<>();
        for (Map<String, Object> layer : List.of(map(project.get("areas"), "areas"), map(pack.get("areas"), "areas"))) {
            layer.forEach((k, v) -> areas.putIfAbsent(k, strings(v, "areas")));
        }
        Set<String> nonReviewable = new LinkedHashSet<>(strings(
                project.getOrDefault("non_reviewable_categories", Classifier.DEFAULT_NON_REVIEWABLE), "non_reviewable_categories"));
        for (String c : nonReviewable) {
            if (!Classifier.CATEGORIES.contains(c)) {
                throw err("unknown non_reviewable category '" + c + "'");
            }
        }
        Map<String, Limits> budgets = new LinkedHashMap<>();
        merged(map(pack.get("budgets"), "budgets"), map(project.get("budgets"), "budgets"))
                .forEach((k, v) -> budgets.put(k, limits(k, v)));
        Map<String, String> tiers = new LinkedHashMap<>();
        merged(map(pack.get("author_tiers"), "author_tiers"), map(project.get("author_tiers"), "author_tiers"))
                .forEach((tier, assoc) -> strings(assoc, "author_tiers").forEach(a -> tiers.put(a.toUpperCase(), tier)));
        Map<String, Object> template = map(project.get("template"), "template");
        Map<String, Object> overrides = map(project.get("overrides"), "overrides");
        Map<String, Map<String, Object>> rawPolicies = new LinkedHashMap<>();
        for (Object src : List.of(pack.getOrDefault("policies", List.of()), project.getOrDefault("policies", List.of()))) {
            mergePolicies(rawPolicies, src);
        }
        Map<String, Object> perPolicy = map(overrides.get("per_policy"), "overrides.per_policy");
        List<Policy> policies = new ArrayList<>();
        for (Map<String, Object> p : rawPolicies.values()) {
            policies.add(policy(p, params, strings(perPolicy.getOrDefault(p.get("id"), List.of()), "overrides.per_policy")));
        }
        return new PolicyConfig(new Classifier(classification, areas, positiveInt(project.getOrDefault("area_depth", 1), "area_depth")),
                nonReviewable, budgets, tiers, strings(template.getOrDefault("required_sections", List.of()), "template"),
                patterns(template.getOrDefault("ignore_patterns", List.of()), "template.ignore_patterns"),
                patterns(project.getOrDefault("linked_issue_patterns", List.of()), "linked_issue_patterns"),
                strings(overrides.getOrDefault("labels", List.of()), "overrides.labels"), params, policies,
                bool(project.getOrDefault("emit_facts", false), "emit_facts"),
                bool(project.getOrDefault("git_facts", true), "git_facts"),
                positiveInt(project.getOrDefault("git_timeout_ms", 10000), "git_timeout_ms"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> pack(String name) {
        if (!ID.matcher(name).matches()) {
            throw err("unknown pack '" + name + "'");
        }
        InputStream in = PolicyConfig.class.getResourceAsStream("packs/" + name + ".yaml");
        if (in == null) {
            throw err("unknown pack '" + name + "' (built-in: oss-maintainer, airflow, strict-agent, none)");
        }
        Map<String, Object> self = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        Object parent = self.remove("extends");
        if (parent == null) {
            return self;
        }
        Map<String, Object> base = pack(parent.toString());
        Map<String, Map<String, Object>> policies = new LinkedHashMap<>();
        mergePolicies(policies, base.getOrDefault("policies", List.of()));
        mergePolicies(policies, self.getOrDefault("policies", List.of()));
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (String k : List.of("params", "budgets", "author_tiers")) {
            out.put(k, merged(map(base.get(k), k), map(self.get(k), k)));
        }
        for (String k : List.of("classification", "areas")) {
            out.put(k, merged(map(self.get(k), k), map(base.get(k), k)));
        }
        out.put("policies", new ArrayList<>(policies.values()));
        return out;
    }

    private static void mergePolicies(Map<String, Map<String, Object>> into, Object src) {
        if (!(src instanceof List<?> list)) {
            throw err("policies must be a list");
        }
        for (Object o : list) {
            Map<String, Object> p = map(o, "policies[]");
            Object id = p.get("id");
            if (id == null || !ID.matcher(id.toString()).matches()) {
                throw err("policy id must be lower-case letters, digits or hyphens, got '" + id + "'");
            }
            for (String k : p.keySet()) {
                if (!POLICY_KEYS.contains(k)) {
                    throw err("policy " + id + ": unknown key '" + k + "'");
                }
            }
            into.computeIfAbsent(id.toString(), x -> new LinkedHashMap<>()).putAll(p);
        }
    }

    private static Policy policy(Map<String, Object> p, Map<String, Object> params, List<String> extraLabels) {
        String id = p.get("id").toString();
        String action = String.valueOf(p.getOrDefault("action", "warn"));
        if (!Set.of("fail", "warn", "off").contains(action)) {
            throw err("policy " + id + ": action must be fail, warn or off");
        }
        if (p.get("when") == null) {
            throw err("policy " + id + ": 'when' is required");
        }
        List<String> labels = new ArrayList<>(strings(p.getOrDefault("override_labels", List.of()), "override_labels"));
        labels.addAll(extraLabels);
        return new Policy(id, Condition.parse(p.get("when"), params, "policy " + id), action,
                bool(p.getOrDefault("overridable", false), "overridable"), labels,
                String.valueOf(p.getOrDefault("message", "Change policy " + id + " matched.")));
    }

    private static Limits limits(String key, Object v) {
        Map<String, Object> m = map(v, "budgets." + key);
        for (String k : m.keySet()) {
            if (!Set.of("files", "lines", "non_test_lines").contains(k)) {
                throw err("budgets." + key + ": unknown limit '" + k + "' (files, lines, non_test_lines)");
            }
        }
        return new Limits(optInt(m.get("files"), key), optInt(m.get("lines"), key), optInt(m.get("non_test_lines"), key));
    }

    private static Integer optInt(Object v, String key) {
        return v == null ? null : positiveInt(v, "budgets." + key);
    }

    static int positiveInt(Object v, String field) {
        if (v instanceof Integer n && n > 0) {
            return n;
        }
        throw err(field + " must be a positive integer, got '" + v + "'");
    }

    private static boolean bool(Object v, String field) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw err(field + " must be true or false");
    }

    private static Map<String, Object> merged(Map<String, Object> base, Map<String, Object> top) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.putAll(top);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v, String field) {
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), val));
            return out;
        }
        throw err(field + " must be a map");
    }

    static List<String> strings(Object v, String field) {
        if (!(v instanceof List<?> l)) {
            throw err(field + " must be a list of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (o == null || o.toString().isBlank()) {
                throw err(field + " must not contain blank entries");
            }
            out.add(o.toString().trim());
        }
        return out;
    }

    private static List<Pattern> patterns(Object v, String field) {
        List<Pattern> out = new ArrayList<>();
        for (String s : strings(v, field)) {
            try {
                out.add(Pattern.compile(s));
            } catch (java.util.regex.PatternSyntaxException e) {
                throw err(field + ": invalid regular expression '" + s + "'");
            }
        }
        return out;
    }

    static IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("change-policy: " + msg);
    }
}
