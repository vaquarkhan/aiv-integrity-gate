/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionAndConfigTest {

    private static final Map<String, Object> FACTS = Map.of("files.reviewable", 12, "intent.class", "fix",
            "meta.labels", List.of("a", "b"), "scope.area_list", "core, ui", "budget.exceeded", true);

    private static Condition.Tri eval(Map<String, Object> node, Set<String> unknown) {
        return Condition.parse(node, Map.of("n", 10), "t").eval(FACTS, unknown);
    }

    private static Condition.Tri eval(Map<String, Object> node) {
        return eval(node, new HashSet<>());
    }

    @Test
    void leafOperators() {
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "files.reviewable", "gt", "${n}")));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "files.reviewable", "gte", 12)));
        assertEquals(Condition.Tri.FALSE, eval(Map.of("fact", "files.reviewable", "lt", 12)));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "files.reviewable", "lte", 12.0)));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "files.reviewable", "eq", 12.0)));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "intent.class", "eq", "FIX")));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "intent.class", "ne", "docs")));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "budget.exceeded", "eq", true)));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "intent.class", "in", List.of("typo", "fix"))));
        assertEquals(Condition.Tri.FALSE, eval(Map.of("fact", "intent.class", "not_in", List.of("fix"))));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "meta.labels", "contains", "b")));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "scope.area_list", "contains", "ui")));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "files.by_category.ci", "eq", 0)), "absent counts are zero");
        assertEquals(Condition.Tri.TRUE, eval(Map.of("fact", "files.by_area.core", "eq", 0)));
        assertThrows(IllegalArgumentException.class, () -> eval(Map.of("fact", "intent.class", "gt", 1)));
    }

    @Test
    void threeValuedLogic() {
        Map<String, Object> unknownLeaf = Map.of("fact", "git.behind_base", "gt", 1);
        Map<String, Object> t = Map.of("fact", "files.reviewable", "gt", 1);
        Map<String, Object> f = Map.of("fact", "files.reviewable", "lt", 1);
        Set<String> unknown = new HashSet<>();
        assertEquals(Condition.Tri.UNKNOWN, eval(unknownLeaf, unknown));
        assertEquals(Set.of("git.behind_base"), unknown);
        assertEquals(Condition.Tri.FALSE, eval(Map.of("all", List.of(unknownLeaf, f))));
        assertEquals(Condition.Tri.UNKNOWN, eval(Map.of("all", List.of(unknownLeaf, t))));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("all", List.of(t, t))));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("any", List.of(unknownLeaf, t))));
        assertEquals(Condition.Tri.UNKNOWN, eval(Map.of("any", List.of(f, unknownLeaf))));
        assertEquals(Condition.Tri.FALSE, eval(Map.of("any", List.of(f, f))));
        assertEquals(Condition.Tri.FALSE, eval(Map.of("not", t)));
        assertEquals(Condition.Tri.TRUE, eval(Map.of("not", f)));
        assertEquals(Condition.Tri.UNKNOWN, eval(Map.of("not", unknownLeaf)));
    }

    @Test
    void parseRejectsBadConditions() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("fact", "files.reviewable");
        withNull.put("eq", null);
        List<Object> bad = new ArrayList<>(Arrays.asList("x", Map.of(), Map.of("all", List.of()), Map.of("any", "x"),
                Map.of("all", List.of(Map.of("fact", "files.reviewable", "gt", 1)), "x", 1),
                Map.of("not", Map.of("fact", "files.reviewable", "gt", 1), "x", 1),
                Map.of("fact", "files.reviewable"), Map.of("fact", "nope", "gt", 1),
                Map.of("fact", "files.by_category.nope", "gt", 1), Map.of("fact", "files.by_area.", "gt", 1),
                Map.of("fact", "files.reviewable", "between", 1), Map.of("fact", "files.reviewable", "in", 1),
                Map.of("fact", "files.reviewable", "eq", List.of(1)), Map.of("fact", "files.reviewable", "gt", "x"),
                Map.of("fact", "files.reviewable", "gt", "${missing}"), withNull,
                Map.of("eq", 1, "gt", 2)));
        for (Object node : bad) {
            assertThrows(IllegalArgumentException.class, () -> Condition.parse(node, Map.of(), "t"), String.valueOf(node));
        }
    }

    @Test
    void renderAndJson() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("files.reviewable", 3);
        facts.put("meta.labels", List.of("x\"y", "z"));
        facts.put("intent.class", "fix\nnow\u0001");
        facts.put("budget.exceeded", false);
        assertEquals("3 files, fix now, ?, 50, labels x\"y, z, 0, {open",
                Facts.render("{files.reviewable} files, {intent.class}, {git.commits}, {max}, labels {meta.labels}, "
                        + "{files.by_category.ci}, {open", facts, Map.of("max", 50)));
        assertEquals("a".repeat(200) + "...", Facts.sanitize("a".repeat(250)));
        assertEquals("{\"files.reviewable\":3,\"meta.labels\":[\"x\\\"y\",\"z\"],"
                + "\"intent.class\":\"fix\\u000anow\\u0001\",\"budget.exceeded\":false}", Facts.toJson(facts));
        assertEquals("\"a\\\\b\"", Facts.quote("a\\b"));
    }

    @Test
    void packsLoadAndMerge() {
        PolicyConfig oss = PolicyConfig.load(Map.of());
        assertEquals(14, oss.policies().size());
        assertEquals(List.of("mega-diff"), oss.policies().stream().filter(p -> !p.action().equals("warn"))
                .map(PolicyConfig.Policy::id).toList(), "mega-diff is the only blocking pack policy");
        assertEquals(50, oss.budgets().get("default").files());
        assertEquals("maintainer", oss.associationTier().get("COLLABORATOR"));
        assertFalse(oss.emitFacts());
        assertTrue(oss.gitFacts());

        PolicyConfig airflow = PolicyConfig.load(Map.of("pack", "airflow"));
        assertEquals(15, airflow.policies().size());
        assertEquals("build-infra", airflow.classifier().category("dev/breeze/x.py"));
        assertEquals("ui", airflow.classifier().area("airflow-core/src/airflow/ui/a.tsx"));
        assertEquals("core", airflow.classifier().area("airflow-core/src/airflow/models/dag.py"));

        PolicyConfig strict = PolicyConfig.load(Map.of("pack", "strict-agent"));
        assertEquals(5, strict.policies().stream().filter(p -> p.action().equals("fail")).count());
        assertEquals(20, strict.budgets().get("default").files());

        assertTrue(PolicyConfig.load(Map.of("pack", "none")).policies().isEmpty());
    }

    @Test
    void projectOverridesPack() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("params", Map.of("cap", 5));
        cfg.put("classification", Map.of("generated", List.of("gen/**")));
        cfg.put("areas", Map.of("core", List.of("core/**")));
        cfg.put("area_depth", 2);
        cfg.put("non_reviewable_categories", List.of("lockfile"));
        cfg.put("budgets", Map.of("external", Map.of("files", 10, "lines", 100), "external.core", Map.of("non_test_lines", 9)));
        cfg.put("author_tiers", Map.of("trusted", List.of("contributor")));
        cfg.put("template", Map.of("required_sections", List.of("Why"), "ignore_patterns", List.of("^Was")));
        cfg.put("linked_issue_patterns", List.of("AIRFLOW-\\d+"));
        cfg.put("overrides", Map.of("labels", List.of("big-ok"), "per_policy", Map.of("custom", List.of("custom-ok"))));
        cfg.put("policies", List.of(Map.of("id", "over-budget", "action", "fail"),
                Map.of("id", "custom", "when", Map.of("fact", "files.changed", "gt", "${cap}"), "overridable", false,
                        "override_labels", List.of("x"))));
        cfg.put("emit_facts", true);
        cfg.put("git_facts", false);
        cfg.put("git_timeout_ms", 50);
        cfg.put("rule_actions", Map.of());
        PolicyConfig p = PolicyConfig.load(cfg);
        assertEquals("generated", p.classifier().category("gen/a.py"));
        assertEquals("core", p.classifier().area("core/a/b.py"));
        assertEquals("x/y", p.classifier().area("x/y/z.py"));
        assertEquals(Set.of("lockfile"), p.nonReviewable());
        assertEquals(9, p.budgets().get("external.core").nonTestLines());
        assertEquals("trusted", p.associationTier().get("CONTRIBUTOR"));
        assertEquals(List.of("Why"), p.requiredSections());
        assertEquals(1, p.templateIgnore().size());
        assertEquals(1, p.linkedIssuePatterns().size());
        assertEquals(List.of("big-ok"), p.overrideLabels());
        PolicyConfig.Policy over = p.policies().stream().filter(x -> x.id().equals("over-budget")).findFirst().orElseThrow();
        assertEquals("fail", over.action());
        assertTrue(over.message().startsWith("Change is over the review budget"), "pack fields kept on partial override");
        PolicyConfig.Policy custom = p.policies().get(p.policies().size() - 1);
        assertEquals(List.of("x", "custom-ok"), custom.overrideLabels());
        assertFalse(custom.overridable());
        assertEquals("Change policy custom matched.", custom.message());
        assertTrue(p.emitFacts());
        assertFalse(p.gitFacts());
        assertEquals(50, p.gitTimeoutMs());
    }

    @Test
    void rejectsInvalidConfig() {
        List<Map<String, Object>> bad = List.of(
                Map.of("nope", 1), Map.of("pack", "missing"), Map.of("pack", "../x"), Map.of("params", "x"),
                Map.of("classification", Map.of("source", List.of("a"))), Map.of("classification", Map.of("nope", List.of("a"))),
                Map.of("classification", Map.of("test", "a")), Map.of("areas", Map.of("a", List.of(" "))),
                Map.of("non_reviewable_categories", List.of("nope")), Map.of("area_depth", 0), Map.of("area_depth", "2"),
                Map.of("budgets", Map.of("default", Map.of("files", -1))), Map.of("budgets", Map.of("default", Map.of("size", 1))),
                Map.of("budgets", Map.of("default", "x")), Map.of("template", Map.of("ignore_patterns", List.of("("))),
                Map.of("policies", "x"), Map.of("policies", List.of("x")), Map.of("policies", List.of(Map.of("id", "Bad"))),
                Map.of("policies", List.of(Map.of("action", "warn"))),
                Map.of("policies", List.of(Map.of("id", "a", "colour", 1))),
                Map.of("policies", List.of(Map.of("id", "a", "action", "block", "when", Map.of("fact", "files.changed", "gt", 1)))),
                Map.of("policies", List.of(Map.of("id", "a"))),
                Map.of("policies", List.of(Map.of("id", "a", "when", Map.of("fact", "files.changed", "gt", 1), "overridable", "yes"))),
                Map.of("emit_facts", "true"), Map.of("linked_issue_patterns", "x"));
        for (Map<String, Object> cfg : bad) {
            assertThrows(IllegalArgumentException.class, () -> PolicyConfig.load(cfg), cfg.toString());
        }
        assertEquals(Map.of(), PolicyConfig.map(null, "x"));
    }
}
