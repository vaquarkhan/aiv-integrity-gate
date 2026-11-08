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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangePolicyGateTest {

    /** Builds a unified diff and the matching changed-file list. */
    private static final class Change {
        final StringBuilder raw = new StringBuilder();
        final List<ChangedFile> files = new ArrayList<>();

        Change file(String path, ChangedFile.ChangeType type, List<String> plus, List<String> minus) {
            files.add(new ChangedFile(path, type, ""));
            raw.append("diff --git a/").append(path).append(" b/").append(path).append("\n--- a/").append(path)
                    .append("\n+++ b/").append(path).append("\n@@ -1 +1 @@\n");
            minus.forEach(l -> raw.append('-').append(l).append('\n'));
            plus.forEach(l -> raw.append('+').append(l).append('\n'));
            return this;
        }

        Change mod(String path, int lines) {
            List<String> plus = new ArrayList<>();
            for (int i = 0; i < lines; i++) {
                plus.add("x" + i + " = " + i);
            }
            return file(path, ChangedFile.ChangeType.MODIFIED, plus, List.of());
        }

        Diff diff() {
            return new Diff("base", "head", files, raw.toString(), 0, 0, null, false);
        }
    }

    private static final GitHistory GIT = (ws, args) -> switch (String.join(" ", args)) {
        case "rev-list --count base..head" -> Optional.of("2\n");
        case "rev-list --count head..base" -> Optional.of("300\n");
        default -> Optional.of("feat: x\u001f\u001e\nFix thing\u001fbody text\u001e\n");
    };

    private static GateResult run(Map<String, Object> cfg, Change c, Map<String, Object> meta, GitHistory git) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(ChangePolicyGate.ID, true, cfg)), Map.of());
        AIVContext ctx = new AIVContext(Paths.get("."), c.diff(), config, meta);
        return new ChangePolicyGate(k -> null, git).evaluate(ctx);
    }

    private static List<String> rules(GateResult r) {
        return r.getFindings().stream().map(Finding::getRuleId).toList();
    }

    private static Map<String, Object> facts(Map<String, Object> cfg, Change c, Map<String, Object> meta, GitHistory git) {
        PolicyConfig pc = PolicyConfig.load(cfg);
        AIVContext ctx = new AIVContext(Paths.get("."), c.diff(), new AIVConfig(List.of(), Map.of()), meta);
        return FactCollector.collect(c.diff(), c.files, new ChangePolicyGate(k -> null, git).meta(ctx), pc, git, Paths.get("."));
    }

    @Test
    void smallCleanChangePassesAndReportsSkippedFacts() {
        Change c = new Change().mod("src/a.py", 5).mod("tests/test_a.py", 3);
        GateResult r = run(Map.of(), c, Map.of(), (ws, a) -> Optional.empty());
        assertEquals("change-policy", new ChangePolicyGate().getId());
        assertTrue(r.isPassed());
        assertTrue(r.getFindings().isEmpty());
        assertTrue(r.getMessage().startsWith("0 change policies matched (0 blocking); skipped, facts unavailable: "), r.getMessage());
        assertTrue(r.getMessage().contains("behind-base (git.behind_base)"), r.getMessage());
        assertTrue(r.getMessage().contains("template-compliance (meta.author_tier, meta.body_words, template.sections_missing)"),
                r.getMessage());
    }

    private static Change messyChange() {
        Change c = new Change();
        for (int i = 0; i < 12; i++) {
            c.mod("src/m" + i + ".py", 20);
        }
        c.mod(".github/workflows/ci.yml", 1).mod("uv.lock", 50);
        for (int i = 0; i < 3; i++) {
            c.file("fmt/f" + i + ".py", ChangedFile.ChangeType.MODIFIED, List.of("a = 1"), List.of("a=1"));
        }
        return c;
    }

    private static Map<String, Object> messyMeta() {
        return Map.of("pr_title", "Fix typo", "pr_body", "Added 22 test cases, all pass.\n- [ ] Yes\nGenerated-by: Claude Code",
                "pr_author_association", " contributor ", "pr_draft", "false", "pr_labels", List.of("unrelated"));
    }

    @Test
    void everyPackPolicyFiresAsAdvisoryOnAMessyChange() {
        Map<String, Object> cfg = Map.of("budgets", Map.of("default", Map.of("files", 5)),
                "template", Map.of("required_sections", List.of("Why")),
                "overrides", Map.of("labels", List.of("large-change-approved")));
        GateResult r = run(cfg, messyChange(), messyMeta(), GIT);
        assertFalse(r.isPassed());
        assertFalse(r.blocksCi(), "pack policies are advisory");
        assertEquals(List.of("policy.over-budget", "policy.small-intent-large-diff", "policy.untested-source-change",
                "policy.infra-with-source", "policy.ci-change-by-non-collaborator", "policy.lockfile-change",
                "policy.formatting-churn", "policy.description-claims-mismatch", "policy.template-compliance",
                "policy.disclosure-consistency", "policy.behind-base"), rules(r));
        assertTrue(r.getFindings().stream().allMatch(f -> f.getMessage().startsWith("[WARN] ") && f.getFilePath().equals("(diff)")));
        assertTrue(r.getFindings().get(0).getMessage().contains("13 reviewable files > 5 (default)"), r.getFindings().get(0).getMessage());
        assertTrue(r.getFindings().get(1).getMessage().contains("Title reads as 'typo' but 13 reviewable files"));
        assertTrue(r.getFindings().get(10).getMessage().contains("300 commits behind"));
        assertEquals("11 change policies matched (0 blocking)", r.getMessage());
    }

    @Test
    void failOverrideRuleActionsAndFactsNote() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("overrides", Map.of("labels", List.of("large-change-approved"), "per_policy",
                Map.of("lockfile-change", List.of("deps-ok"))));
        cfg.put("policies", List.of(Map.of("id", "over-budget", "action", "fail"),
                Map.of("id", "untested-source-change", "action", "fail", "overridable", false)));
        cfg.put("budgets", Map.of("default", Map.of("files", 5)));
        cfg.put("rule_actions", Map.of("policy.formatting-churn", "off"));
        cfg.put("emit_facts", true);
        Map<String, Object> meta = new HashMap<>(messyMeta());
        meta.put("pr_labels", List.of("large-change-approved", "deps-ok"));
        GateResult r = run(cfg, messyChange(), meta, GIT);
        assertFalse(r.isPassed());
        assertTrue(r.blocksCi());
        List<String> rules = rules(r);
        assertEquals("change-policy.override", rules.get(0));
        assertEquals("[POLICY-OVERRIDE] over-budget by label large-change-approved", r.getFindings().get(0).getMessage());
        assertTrue(r.getFindings().get(2).getMessage().startsWith("[FAIL] 240 source lines changed"), r.getFindings().get(2).getMessage());
        assertTrue(r.getFindings().stream().anyMatch(f -> f.getMessage().equals("[POLICY-OVERRIDE] lockfile-change by label deps-ok")));
        assertFalse(rules.contains("policy.formatting-churn"));
        Finding note = r.getFindings().get(r.getFindings().size() - 1);
        assertEquals(ChangePolicyGate.FACTS_RULE, note.getRuleId());
        assertTrue(note.getMessage().startsWith("{\"files.changed\":17,\"files.reviewable\":13,"), note.getMessage());
        assertTrue(note.getMessage().contains("\"meta.labels\":[\"large-change-approved\",\"deps-ok\"]"));
        assertEquals("change-policy.override", rules.get(1), "small-intent-large-diff is overridable in the pack");
        assertEquals("policy.infra-with-source", rules.get(3), "not overridable: the project-wide label does not apply");
        assertTrue(r.getMessage().startsWith("7 change policies matched (1 blocking)"), r.getMessage());
        assertFalse(r.getAdvisoryRules().contains("policy.untested-source-change"));
        assertTrue(r.getAdvisoryRules().containsAll(List.of("policy.infra-with-source", ChangePolicyGate.OVERRIDE_RULE,
                ChangePolicyGate.FACTS_RULE)), r.getAdvisoryRules().toString());
    }

    @Test
    void factsCountKindsAreasAndHistory() {
        Change c = new Change().mod("core/a.py", 2).mod("core/tests/test_a.py", 1).mod("ui/b.ts", 2)
                .file("new.py", ChangedFile.ChangeType.ADDED, List.of("def test_new():"), List.of())
                .file("old.py", ChangedFile.ChangeType.DELETED, List.of(), List.of("x"))
                .file("docs/r.md", ChangedFile.ChangeType.MODIFIED, List.of("doc"), List.of());
        c.files.add(new ChangedFile("moved.py", ChangedFile.ChangeType.RENAMED, "", "orig.py", null, null, null, null,
                null, null, null));
        c.files.add(new ChangedFile(null, ChangedFile.ChangeType.MODIFIED, ""));
        Map<String, Object> cfg = Map.of("areas", Map.of("core", List.of("core/**"), "ui", List.of("ui/**")),
                "budgets", Map.of("external", Map.of("files", 100), "external.core", Map.of("non_test_lines", 1),
                        "ui", Map.of("lines", 1, "files", 1)));
        Map<String, Object> f = facts(cfg, c, Map.of("pr_author_association", "NONE"), GIT);
        assertEquals(7, f.get("files.changed"));
        assertEquals(6, f.get("files.reviewable"));
        assertEquals(1, f.get("files.added"));
        assertEquals(1, f.get("files.deleted"));
        assertEquals(1, f.get("files.renamed"));
        assertEquals(1, f.get("files.by_area.ui"));
        assertEquals(8, f.get("lines.changed"));
        assertEquals(1, f.get("lines.deleted"));
        assertEquals(6, f.get("lines.source_changed"));
        assertEquals(1, f.get("lines.test_changed"));
        assertEquals(1, f.get("lines.docs_changed"));
        assertEquals(7, f.get("lines.non_test_changed"));
        assertEquals("(root), core, docs, ui", f.get("scope.area_list"));
        assertEquals(1, f.get("tests.files_changed"));
        assertEquals(0, f.get("tests.functions_added"), "def test_ in a non-test file is not counted");
        assertEquals(2, f.get("tests.source_areas_without_tests"), "(root) and ui have source but no tests");
        assertEquals(true, f.get("budget.exceeded"));
        assertEquals("core: 2 non-test lines > 1 (external.core); ui: 2 changed lines > 1 (ui)", f.get("budget.detail"));
        assertEquals("external", f.get("meta.author_tier"));
        assertEquals(2, f.get("git.commits"));
        assertEquals(300, f.get("git.behind_base"));
        assertEquals(1, f.get("git.commits_without_body"));
        assertEquals(1, f.get("git.conventional_prefix_commits"));
        assertNull(f.get("intent.class"));
        assertNull(f.get("meta.body_words"));

        Map<String, Object> maintainer = facts(cfg, c, Map.of("pr_author_association", "OWNER"), (ws, a) -> Optional.of("abc"));
        assertEquals("maintainer", maintainer.get("meta.author_tier"));
        assertEquals("ui: 2 changed lines > 1 (ui)", maintainer.get("budget.detail"), "no maintainer budget: default applies");
        assertNull(maintainer.get("git.commits"), "non-numeric output is unknown");
        assertEquals(1, maintainer.get("git.commits_without_body"));

        Map<String, Object> noGit = new LinkedHashMap<>(cfg);
        noGit.put("git_facts", false);
        assertNull(facts(noGit, c, Map.of(), GIT).get("git.commits"));
        Change noRefs = new Change().mod("a.py", 1);
        Diff blankRefs = new Diff(" ", "head", noRefs.files, noRefs.raw.toString(), 0, 0, null, false);
        assertNull(FactCollector.collect(blankRefs, noRefs.files, new FactCollector.Meta(null, null, null, null, null),
                PolicyConfig.load(Map.of()), GIT, Paths.get(".")).get("git.commits"));
        Diff nullRefs = new Diff(null, null, noRefs.files, noRefs.raw.toString(), 0, 0, null, false);
        assertNull(FactCollector.collect(nullRefs, noRefs.files, new FactCollector.Meta(null, null, null, null, null),
                PolicyConfig.load(Map.of()), GIT, Paths.get(".")).get("git.commits"));
        Diff headless = new Diff("base", "", noRefs.files, noRefs.raw.toString(), 0, 0, null, false);
        assertNull(FactCollector.collect(headless, noRefs.files, new FactCollector.Meta(null, null, null, null, null),
                PolicyConfig.load(Map.of()), GIT, Paths.get(".")).get("git.commits"));
        Diff headNull = new Diff("base", null, noRefs.files, noRefs.raw.toString(), 0, 0, null, false);
        assertNull(FactCollector.collect(headNull, noRefs.files, new FactCollector.Meta(null, null, null, null, null),
                PolicyConfig.load(Map.of()), GIT, Paths.get(".")).get("git.commits"));
    }

    @Test
    void wholeBudgetLimitsAndClaims() {
        Change c = new Change().mod("src/a.py", 30).mod("tests/test_a.py", 0)
                .file("tests/test_b.py", ChangedFile.ChangeType.MODIFIED, List.of("def test_one():", "def test_two():"), List.of());
        Map<String, Object> cfg = Map.of("budgets", Map.of("default", Map.of("lines", 10, "non_test_lines", 5)));
        Map<String, Object> f = facts(cfg, c, Map.of("pr_title", "Add thing", "pr_body", "Adds 3 tests. Fixes #4\n"
                + "<!-- Was generative AI used --> ## Notes"), GIT);
        assertEquals("32 changed lines > 10 (default); 30 non-test lines > 5 (default)", f.get("budget.detail"));
        assertEquals(2, f.get("tests.functions_added"));
        assertEquals(3, f.get("claims.tests_count"));
        assertEquals(1, f.get("claims.tests_overclaim"));
        assertEquals(true, f.get("claims.tests_added"));
        assertEquals(true, f.get("meta.linked_issue"));
        assertEquals("none missing", f.get("template.missing_list"));
        assertEquals(false, f.get("claims.ai_disclosure_mismatch"));
        assertEquals("feature", f.get("intent.class"));

        Map<String, Object> within = facts(Map.of(), new Change().mod("src/a.py", 1), Map.of("pr_body", "no claims"), GIT);
        assertEquals(false, within.get("budget.exceeded"));
        assertEquals("within budget", within.get("budget.detail"));
        assertEquals(0, within.get("claims.tests_overclaim"));
        assertEquals(false, within.get("meta.linked_issue"));
    }

    @Test
    void megaDiffBlocksAndCanBeOverridden() {
        Change c = new Change();
        for (int i = 0; i < 1000; i++) {
            c.files.add(new ChangedFile("pkg/f" + i + ".py", ChangedFile.ChangeType.MODIFIED, ""));
        }
        Map<String, Object> cfg = Map.of("overrides", Map.of("labels", List.of("mass-change-ok")));
        GateResult blocked = run(cfg, c, Map.of(), (ws, a) -> Optional.empty());
        assertTrue(blocked.blocksCi());
        assertEquals("policy.mega-diff", blocked.getFindings().get(0).getRuleId());
        assertTrue(blocked.getFindings().get(0).getMessage().startsWith("[FAIL] 1000 files changed."));
        GateResult overridden = run(cfg, c, Map.of("pr_labels", List.of("mass-change-ok")), (ws, a) -> Optional.empty());
        assertFalse(overridden.blocksCi());
        assertEquals("[POLICY-OVERRIDE] mega-diff by label mass-change-ok", overridden.getFindings().get(0).getMessage());
    }

    @Test
    void smallFixHugeDiffUsesTheRawDiffSize() {
        Change c = new Change().mod("src/a.py", 3);
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(ChangePolicyGate.ID, true, Map.of())), Map.of());
        Diff big = new Diff("base", "head", c.files, c.raw.toString(), 4500, 600, null, false);
        GateResult r = new ChangePolicyGate(k -> null, (ws, a) -> Optional.empty()).evaluate(
                new AIVContext(Paths.get("."), big, config, Map.of("pr_title", "Fix typo", "pr_body", "A long enough body "
                        + "that explains the reason for this change and how it was tested by hand locally today.")));
        assertEquals(List.of("policy.small-fix-huge-diff"), rules(r));
        assertTrue(r.getFindings().get(0).getMessage().contains("the diff changes 5100 lines"), r.getFindings().get(0).getMessage());
    }

    @Test
    void largeNewCodeUnits() {
        List<String> longFn = new ArrayList<>(List.of("class Big:", "    def run(self):"));
        for (int i = 0; i < 160; i++) {
            longFn.add("        x" + i + " = " + i);
        }
        longFn.addAll(List.of("def stub_a():", "    \"\"\"Doc.\"\"\"", "", "    pass", "func stubB() {", "    panic(\"not implemented\")",
                "fn real() {", "    let x = 1;", "def trailing():"));
        int[] shape = FactCollector.codeShape(longFn);
        assertEquals(160, shape[0]);
        assertEquals(2, shape[1]);
        assertEquals(0, FactCollector.codeShape(List.of("x = 1", "class A:", "    y = 2"))[0], "class bodies are not functions");

        Change c = new Change().file("src/new_module.py", ChangedFile.ChangeType.ADDED, longFn, List.of());
        Map<String, Object> f = facts(Map.of(), c, Map.of(), GIT);
        assertEquals(0, f.get("code.large_new_files"), "171 lines is under the 500-line threshold");
        assertEquals(160, f.get("code.max_function_lines"));
        assertEquals(2, f.get("code.stub_functions"));
        Change big = new Change().mod("src/a.py", 0);
        big.files.clear();
        big.file("src/huge.py", ChangedFile.ChangeType.ADDED, java.util.Collections.nCopies(500, "y = 1"), List.of())
                .file("tests/test_huge.py", ChangedFile.ChangeType.ADDED, java.util.Collections.nCopies(600, "z = 1"), List.of());
        Map<String, Object> g = facts(Map.of(), big, Map.of(), GIT);
        assertEquals(1, g.get("code.large_new_files"), "test files do not count");
        GateResult r = run(Map.of(), c, Map.of(), (ws, a) -> Optional.empty());
        assertTrue(rules(r).contains("policy.large-new-code-unit"), rules(r).toString());
    }

    @Test
    void metadataSources() {
        Map<String, String> env = Map.of("AIV_PR_TITLE", "Env title", "AIV_PR_LABELS", "[\"a\\\"b\",\"c\"]",
                "AIV_PR_DRAFT", " TRUE ", "AIV_PR_AUTHOR_ASSOCIATION", "MEMBER");
        ChangePolicyGate gate = new ChangePolicyGate(env::get, null);
        AIVContext empty = new AIVContext(Paths.get("."), new Diff("a", "b", List.of(), ""), new AIVConfig(List.of(), Map.of()));
        FactCollector.Meta m = gate.meta(empty);
        assertEquals("Env title", m.title());
        assertEquals("", m.body(), "a PR run with no description has an empty body, not an unknown one");
        assertEquals(List.of("a\"b", "c"), m.labels());
        assertEquals(true, m.draft());
        assertEquals("MEMBER", m.authorAssociation());

        FactCollector.Meta fromMeta = gate.meta(empty.withMetadata("pr_labels", "[]").withMetadata("pr_title", " "));
        assertEquals(List.of(), fromMeta.labels());
        assertEquals("Env title", fromMeta.title(), "blank metadata falls back to the environment");

        Function<String, String> none = k -> null;
        FactCollector.Meta local = new ChangePolicyGate(none, null).meta(empty);
        assertNull(local.title());
        assertNull(local.body());
        assertNull(local.labels());
        assertNull(local.draft());
        assertNull(local.authorAssociation());
        assertNull(new ChangePolicyGate(k -> " ", null).meta(empty).labels());
        assertEquals(List.of(), ChangePolicyGate.jsonStrings("null"));
    }

    @Test
    void excludedFilesCountTowardsReviewCost() {
        Change c = new Change().mod("src/a.py", 1).mod("gen/b.py", 1);
        ChangedFile excluded = c.files.remove(1);
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(ChangePolicyGate.ID, true,
                Map.of("emit_facts", true, "pack", "none"))), Map.of());
        AIVContext ctx = new AIVContext(Paths.get("."), c.diff(), config,
                Map.of(AIVContext.EXCLUDED_CHANGED_FILES, List.of("ignored", excluded)));
        GateResult r = new ChangePolicyGate(k -> null, (ws, a) -> Optional.empty()).evaluate(ctx);
        assertTrue(r.isPassed());
        assertEquals("0 change policies matched (0 blocking)", r.getMessage());
        assertTrue(r.getFindings().get(0).getMessage().contains("\"files.changed\":2"), r.getFindings().get(0).getMessage());
    }

    @Test
    void defaultConstructorUsesRealGitAndToleratesNoRepository(@TempDir Path dir) {
        Change c = new Change().mod("a.py", 1);
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(ChangePolicyGate.ID, true,
                Map.of("pack", "none", "git_timeout_ms", 5000))), Map.of());
        GateResult r = new ChangePolicyGate().evaluate(new AIVContext(dir, c.diff(), config));
        assertTrue(r.isPassed());
        GateResult noEntry = new ChangePolicyGate(k -> null, (ws, a) -> Optional.empty())
                .evaluate(new AIVContext(dir, c.diff(), new AIVConfig(List.of(), Map.of())));
        assertTrue(noEntry.isPassed(), noEntry.getMessage());
    }
}
