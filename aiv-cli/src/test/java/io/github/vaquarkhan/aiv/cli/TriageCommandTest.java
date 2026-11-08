/*
 * Copyright 2026 Viquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.util.JsonLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** @author Viquar Khan */
class TriageCommandTest {

    @Test
    void replayProducesEveryProposalAndNoSideEffects(@TempDir Path dir) throws Exception {
        List<Map<String, Object>> prs = new ArrayList<>();
        prs.add(pr(1, Map.of("failing_open_prs", 4)));
        prs.add(pr(2, Map.of("open_prs", 6)));
        prs.add(pr(3, Map.of("draft", true, "last_activity_at", "2026-10-01T00:00:00Z")));
        prs.add(pr(4, Map.of("required_failed", 1)));
        prs.add(pr(5, Map.of("conflicts", true)));
        prs.add(pr(6, Map.of("unresolved_threads", 2)));
        prs.add(pr(7, Map.of("definition_touched", true)));
        prs.add(pr(8, Map.of("prior_merged_prs", 0, "lines_changed", 600, "accepted", false)));
        prs.add(pr(9, Map.of()));
        prs.add(pr(10, Map.of("association", "MEMBER")));
        Path input = write(dir.resolve("snapshot.json"), snapshot(prs));
        Path policy = write(dir.resolve("policy.yaml"), policy(allRules()));
        Path json = dir.resolve("out/result.json");
        Path markdown = dir.resolve("out/result.md");

        assertEquals(0, Main.run(new String[]{"triage", "replay", "--input", input.toString(), "--policy", policy.toString(),
                "--output-json", json.toString(), "--output-markdown", markdown.toString()}));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) JsonLite.parse(Files.readString(json));
        assertEquals(TriageCommand.RESULT_SCHEMA, result.get("schema_version"));
        assertEquals("dry-run", result.get("mode"));
        assertEquals(List.of(), result.get("side_effects"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(15L, summary.get("proposals"));
        @SuppressWarnings("unchecked")
        List<Object> decisions = (List<Object>) result.get("decisions");
        assertEquals(10, decisions.size());
        assertAction(decisions, 1, "close-pr", "close-author-quality-violations");
        assertAction(decisions, 2, "hold-open-pr-limit", "hold-open-pr-limit");
        assertAction(decisions, 3, "close-pr", "close-stale-draft");
        assertAction(decisions, 4, "draft-pr", "draft-required-ci-failure");
        assertAction(decisions, 5, "request-rebase", "rebase-conflicted-or-stale-branch");
        assertAction(decisions, 6, "hold-review", "hold-unresolved-review");
        assertAction(decisions, 7, "require-maintainer-ci-approval", "hold-external-ci-definition-change");
        assertAction(decisions, 8, "require-maintainer-permission", "hold-large-newcomer-without-accepted-issue");
        assertAction(decisions, 9, "mark-ready", "ready-for-maintainer-review");
        assertNoActions(decisions, 10);
        String report = Files.readString(markdown);
        assertTrue(report.contains("No GitHub API call, comment, label, draft, close, assignment or CI rerun was attempted."));
        assertTrue(report.contains("PR #9"));
        assertFalse(report.contains("PR #10\n\n- **"));
    }

    @Test
    void unknownFieldsSkipRulesRatherThanGuessing(@TempDir Path dir) throws Exception {
        Map<String, Object> incomplete = pr(12, Map.of("remove", List.of("checks", "review", "branch", "ci", "draft")));
        Path input = write(dir.resolve("input.json"), snapshot(List.of(incomplete)));
        Path policy = write(dir.resolve("policy.yaml"), policy(allRules()));
        Path json = dir.resolve("result.json");
        Path md = dir.resolve("result.md");
        assertEquals(0, Main.run(new String[]{"triage", "replay", "--input", input.toString(), "--policy", policy.toString(),
                "--output-json", json.toString(), "--output-markdown", md.toString()}));
        String text = Files.readString(md);
        assertTrue(text.contains("Unavailable facts"));
        assertTrue(text.contains("checks.required_failed"));
        assertTrue(text.contains("ready-for-maintainer-review"));
    }

    @Test
    void asOfControlsStaleDraftDeterministically(@TempDir Path dir) throws Exception {
        Map<String, Object> stale = pr(13, Map.of("draft", true, "last_activity_at", "2026-10-20T00:00:00Z"));
        Path input = write(dir.resolve("input.json"), snapshot(List.of(stale)));
        Path policy = write(dir.resolve("policy.yaml"), policy(List.of("close-stale-draft")));
        Path jsonA = dir.resolve("a.json");
        Path mdA = dir.resolve("a.md");
        assertEquals(0, Main.run(new String[]{"triage", "replay", "--input", input.toString(), "--policy", policy.toString(),
                "--output-json", jsonA.toString(), "--output-markdown", mdA.toString(), "--as-of", "2026-10-26T00:00:00Z"}));
        assertFalse(Files.readString(mdA).contains("close-pr"));
        Path jsonB = dir.resolve("b.json");
        Path mdB = dir.resolve("b.md");
        assertEquals(0, Main.run(new String[]{"triage", "replay", "--input", input.toString(), "--policy", policy.toString(),
                "--output-json", jsonB.toString(), "--output-markdown", mdB.toString(), "--as-of", "2026-10-28T00:00:00Z"}));
        assertTrue(Files.readString(mdB).contains("close-pr"));
        assertNotEquals(Files.readString(jsonA), Files.readString(jsonB));
    }

    @Test
    void replayIsByteStableForSameInput(@TempDir Path dir) throws Exception {
        Path input = write(dir.resolve("input.json"), snapshot(List.of(pr(14, Map.of()))));
        Path policy = write(dir.resolve("policy.yaml"), policy(List.of("ready-for-maintainer-review")));
        Path one = dir.resolve("one.json");
        Path two = dir.resolve("two.json");
        Path oneMd = dir.resolve("one.md");
        Path twoMd = dir.resolve("two.md");
        String[] first = {"triage", "replay", "--input", input.toString(), "--policy", policy.toString(), "--output-json", one.toString(),
                "--output-markdown", oneMd.toString(), "--as-of", "2026-10-24T00:00:00Z"};
        String[] second = {"triage", "replay", "--input", input.toString(), "--policy", policy.toString(), "--output-json", two.toString(),
                "--output-markdown", twoMd.toString(), "--as-of", "2026-10-24T00:00:00Z"};
        assertEquals(0, Main.run(first));
        assertEquals(0, Main.run(second));
        assertEquals(Files.readString(one), Files.readString(two));
        assertEquals(Files.readString(oneMd), Files.readString(twoMd));
    }

    @Test
    void argsAndSchemaAreStrict(@TempDir Path dir) throws Exception {
        Path input = write(dir.resolve("input.json"), snapshot(List.of(pr(15, Map.of()))));
        Path policy = write(dir.resolve("policy.yaml"), policy(List.of("ready-for-maintainer-review")));
        Path json = dir.resolve("x.json");
        Path md = dir.resolve("x.md");
        List<String[]> badArgs = List.of(new String[]{}, new String[]{"nope"}, new String[]{"replay"},
                new String[]{"replay", "--input", input.toString()},
                new String[]{"replay", "--input", input.toString(), "--input", input.toString(), "--policy", policy.toString(),
                        "--output-json", json.toString(), "--output-markdown", md.toString()},
                new String[]{"replay", "--input", input.toString(), "--policy", policy.toString(), "--output-json", json.toString(),
                        "--output-markdown", md.toString(), "--publish-github-checks"},
                new String[]{"replay", "--input", input.toString(), "--policy", policy.toString(), "--output-json", json.toString(),
                        "--output-markdown", md.toString(), "--as-of", "yesterday"});
        for (String[] args : badArgs) assertEquals(2, TriageCommand.run(args), String.join(" ", args));

        assertEquals(2, runMalformed(dir, "{\"schema_version\":\"bad\",\"repository\":\"x\",\"captured_at\":\"2026-10-24T00:00:00Z\",\"pull_requests\":[]}", policy));
        assertEquals(2, runMalformed(dir, "{\"schema_version\":\"aiv-triage-snapshot/1\",\"repository\":\"x\",\"captured_at\":\"bad\",\"pull_requests\":[]}", policy));
        assertEquals(2, runMalformed(dir, "{\"schema_version\":\"aiv-triage-snapshot/1\",\"repository\":\"x\",\"captured_at\":\"2026-10-24T00:00:00Z\",\"unknown\":true,\"pull_requests\":[{\"number\":1}]}", policy));
        Path invalidPolicy = write(dir.resolve("invalid.yaml"), "schema_version: aiv-triage-policy/1\nname: x\nthresholds: {}\nrules: []\n");
        assertEquals(2, Main.run(command(input, invalidPolicy, json, md)));
    }

    @Test
    void directParsersRejectBadNestedTypesAndRuleDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Snapshot.parse(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Snapshot.parse(Map.of("schema_version", TriageCommand.SNAPSHOT_SCHEMA,
                "repository", "x", "captured_at", "2026-10-24T00:00:00Z", "pull_requests", List.of(Map.of("number", 0)))));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Policy.parse(Map.of("schema_version", TriageCommand.POLICY_SCHEMA,
                "name", "x", "thresholds", Map.of("external_associations", List.of("NONE"), "newcomer_prior_merged_prs", 0,
                        "newcomer_large_change_lines", 1, "max_open_prs", 1, "max_failing_open_prs", 1, "stale_draft_days", 1,
                        "behind_base_commits", 1), "rules", List.of(Map.of("id", "not-a-rule", "enabled", true)))));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Policy.parse(Map.of("schema_version", TriageCommand.POLICY_SCHEMA,
                "name", "x", "thresholds", Map.of("external_associations", "NONE"), "rules", List.of())));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Args.parse(new String[]{"replay", "--input", "x", "--policy", "y",
                "--output-json", "z", "--output-markdown", "m", "--as-of", ""}));
    }

    @SuppressWarnings("unchecked")
    private static void assertAction(List<Object> decisions, long number, String action, String rule) {
        Map<String, Object> d = decision(decisions, number);
        List<Object> proposals = (List<Object>) d.get("proposals");
        assertTrue(proposals.stream().map(v -> (Map<String, Object>) v)
                .anyMatch(p -> action.equals(p.get("action")) && rule.equals(p.get("rule_id"))), number + " " + proposals);
    }

    @SuppressWarnings("unchecked")
    private static void assertNoActions(List<Object> decisions, long number) {
        assertEquals(List.of(), decision(decisions, number).get("proposals"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decision(List<Object> decisions, long number) {
        return decisions.stream().map(v -> (Map<String, Object>) v).filter(d -> ((Number) d.get("number")).longValue() == number)
                .findFirst().orElseThrow();
    }

    private static int runMalformed(Path dir, String snapshot, Path policy) throws Exception {
        Path input = write(dir.resolve("malformed-" + Math.abs(snapshot.hashCode()) + ".json"), snapshot);
        return Main.run(command(input, policy, dir.resolve("out.json"), dir.resolve("out.md")));
    }

    private static String[] command(Path input, Path policy, Path json, Path md) {
        return new String[]{"triage", "replay", "--input", input.toString(), "--policy", policy.toString(),
                "--output-json", json.toString(), "--output-markdown", md.toString()};
    }

    private static Path write(Path path, String value) throws Exception {
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private static String snapshot(List<Map<String, Object>> prs) {
        return JsonLite.write(Map.of("schema_version", TriageCommand.SNAPSHOT_SCHEMA, "repository", "example/project",
                "captured_at", "2026-10-24T00:00:00Z", "pull_requests", prs));
    }

    private static String policy(List<String> rules) {
        StringBuilder y = new StringBuilder("schema_version: aiv-triage-policy/1\nname: test-policy\nlabels:\n"
                + "  ready: ready\n  needs_permission: permission\n  ci_safety: ci-safety\nthresholds:\n"
                + "  external_associations: [CONTRIBUTOR, FIRST_TIME_CONTRIBUTOR, FIRST_TIMER, NONE]\n"
                + "  newcomer_prior_merged_prs: 0\n  newcomer_large_change_lines: 500\n  max_open_prs: 5\n"
                + "  max_failing_open_prs: 3\n  stale_draft_days: 7\n  behind_base_commits: 100\nrules:\n");
        for (String rule : rules) y.append("  - id: ").append(rule).append("\n    enabled: true\n");
        return y.toString();
    }

    private static List<String> allRules() {
        return List.of("close-author-quality-violations", "hold-open-pr-limit", "close-stale-draft", "draft-required-ci-failure",
                "rebase-conflicted-or-stale-branch", "hold-unresolved-review", "hold-external-ci-definition-change",
                "hold-large-newcomer-without-accepted-issue", "ready-for-maintainer-review");
    }

    private static Map<String, Object> pr(long number, Map<String, Object> changes) {
        Map<String, Object> author = new LinkedHashMap<>(Map.of("login", "new-user", "association", "CONTRIBUTOR",
                "prior_merged_prs", 0, "open_prs", 1, "failing_open_prs", 0, "last_activity_at", "2026-10-23T00:00:00Z"));
        Map<String, Object> checks = new LinkedHashMap<>(Map.of("required_failed", 0, "required_missing", 0, "conflicts", false));
        Map<String, Object> review = new LinkedHashMap<>(Map.of("unresolved_threads", 0, "maintainer_engaged", false));
        Map<String, Object> branch = new LinkedHashMap<>(Map.of("commits_behind_base", 0));
        Map<String, Object> issue = new LinkedHashMap<>(Map.of("linked", true, "accepted", true));
        Map<String, Object> ci = new LinkedHashMap<>(Map.of("definition_touched", false));
        Map<String, Object> files = new LinkedHashMap<>(Map.of("changed", 2, "lines_changed", 80));
        Map<String, Object> pr = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("number", number), Map.entry("title", "Fix test"), Map.entry("body", "Fixes #1."),
                Map.entry("created_at", "2026-10-23T00:00:00Z"), Map.entry("updated_at", "2026-10-23T00:00:00Z"),
                Map.entry("draft", false), Map.entry("labels", List.of()), Map.entry("author", author),
                Map.entry("checks", checks), Map.entry("review", review), Map.entry("branch", branch),
                Map.entry("issue", issue), Map.entry("ci", ci), Map.entry("files", files)));
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            switch (e.getKey()) {
                case "failing_open_prs", "prior_merged_prs", "open_prs", "association", "last_activity_at" -> author.put(e.getKey(), e.getValue());
                case "required_failed", "required_missing", "conflicts" -> checks.put(e.getKey(), e.getValue());
                case "unresolved_threads", "maintainer_engaged" -> review.put(e.getKey(), e.getValue());
                case "commits_behind_base" -> branch.put(e.getKey(), e.getValue());
                case "linked", "accepted" -> issue.put(e.getKey(), e.getValue());
                case "definition_touched" -> ci.put(e.getKey(), e.getValue());
                case "changed", "lines_changed" -> files.put(e.getKey(), e.getValue());
                case "draft", "labels" -> pr.put(e.getKey(), e.getValue());
                case "remove" -> ((List<?>) e.getValue()).forEach(k -> pr.remove(String.valueOf(k)));
                default -> throw new IllegalArgumentException("test change key " + e.getKey());
            }
        }
        return pr;
    }

    @Test
    void missingFieldsAndBranchVariantsAreEvidenceNotDefaults(@TempDir Path dir) throws Exception {
        Map<String, Object> missingAuthor = pr(20, Map.of("failing_open_prs", 0, "open_prs", 6));
        @SuppressWarnings("unchecked")
        Map<String, Object> author = (Map<String, Object>) missingAuthor.get("author");
        author.remove("failing_open_prs");
        Map<String, Object> staleMissingActivity = pr(21, Map.of("draft", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> staleAuthor = (Map<String, Object>) staleMissingActivity.get("author");
        staleAuthor.remove("last_activity_at");
        Map<String, Object> missingRequired = pr(22, Map.of("required_failed", 0));
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) missingRequired.get("checks");
        checks.remove("required_missing");
        Map<String, Object> behind = pr(23, Map.of("commits_behind_base", 101));
        Map<String, Object> missingNewcomer = pr(24, Map.of("prior_merged_prs", 0, "lines_changed", 600, "accepted", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> newAuthor = (Map<String, Object>) missingNewcomer.get("author");
        newAuthor.remove("prior_merged_prs");
        Map<String, Object> noAssociation = pr(25, Map.of("association", "NONE"));
        @SuppressWarnings("unchecked")
        Map<String, Object> noAssocAuthor = (Map<String, Object>) noAssociation.get("author");
        noAssocAuthor.remove("association");
        noAssociation.remove("labels");
        Path input = write(dir.resolve("missing.json"), snapshot(List.of(missingAuthor, staleMissingActivity, missingRequired, behind,
                missingNewcomer, noAssociation)));
        Path policy = write(dir.resolve("policy.yaml"), policy(allRules()));
        Path json = dir.resolve("out.json");
        Path md = dir.resolve("out.md");
        assertEquals(0, Main.run(command(input, policy, json, md)));
        String report = Files.readString(md);
        assertTrue(report.contains("author.failing_open_prs"));
        assertTrue(report.contains("author.last_activity_at"));
        assertTrue(report.contains("checks.required_missing"));
        assertTrue(report.contains("author.prior_merged_prs"));
        assertTrue(report.contains("request-rebase"));
        assertTrue(report.contains("author.association"));
    }

    @Test
    void parserAndIoFailuresAreRejected(@TempDir Path dir) throws Exception {
        Path policy = write(dir.resolve("policy.yaml"), policy(List.of("ready-for-maintainer-review")));
        Path json = dir.resolve("x.json");
        Path md = dir.resolve("x.md");
        assertEquals(2, Main.run(command(dir.resolve("missing.json"), policy, json, md)));
        Path listPolicy = write(dir.resolve("list.yaml"), "- not-a-map\n");
        Path input = write(dir.resolve("input.json"), snapshot(List.of(pr(26, Map.of()))));
        assertEquals(2, Main.run(command(input, listPolicy, json, md)));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Snapshot.parse(Map.of("schema_version", TriageCommand.SNAPSHOT_SCHEMA,
                "repository", "x", "captured_at", "2026-10-24T00:00:00Z", "pull_requests", List.of(pr(1, Map.of()), pr(1, Map.of())))));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Snapshot.parse(Map.of("schema_version", TriageCommand.SNAPSHOT_SCHEMA,
                "repository", "x", "captured_at", "2026-10-24T00:00:00Z", "pull_requests", List.of())));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Snapshot.parse(Map.of("schema_version", TriageCommand.SNAPSHOT_SCHEMA,
                "repository", "x", "captured_at", "2026-10-24T00:00:00Z", "pull_requests", List.of("not-map"))));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Snapshot.parse(Map.of("schema_version", TriageCommand.SNAPSHOT_SCHEMA,
                "repository", "x", "captured_at", "2026-10-24T00:00:00Z", "pull_requests", List.of(pr(27, Map.of("draft", "no"))))));
        Map<String, Object> labelsBlank = pr(28, Map.of("labels", List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Snapshot.parse(Map.of("schema_version", TriageCommand.SNAPSHOT_SCHEMA,
                "repository", "x", "captured_at", "2026-10-24T00:00:00Z", "pull_requests", List.of(labelsBlank))));
    }

    @Test
    void policyParserCoversLabelsDisabledRulesAndStrictValues() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("external_associations", List.of("none"));
        thresholds.put("newcomer_prior_merged_prs", 0);
        thresholds.put("newcomer_large_change_lines", 1);
        thresholds.put("max_open_prs", 1);
        thresholds.put("max_failing_open_prs", 1);
        thresholds.put("stale_draft_days", 1);
        thresholds.put("behind_base_commits", 1);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("schema_version", TriageCommand.POLICY_SCHEMA);
        base.put("name", "x");
        base.put("thresholds", thresholds);
        base.put("rules", List.of(Map.of("id", "ready-for-maintainer-review", "enabled", false)));
        TriageCommand.Policy p = TriageCommand.Policy.parse(base);
        assertEquals("ready-for-maintainer-review", p.readyLabel());
        assertTrue(p.enabledRules().isEmpty());
        base.put("labels", Map.of("ready", "custom-ready", "needs_permission", "permission", "ci_safety", "ci"));
        p = TriageCommand.Policy.parse(base);
        assertEquals("custom-ready", p.readyLabel());
        Map<String, Object> duplicate = new LinkedHashMap<>(base);
        duplicate.put("rules", List.of(Map.of("id", "ready-for-maintainer-review", "enabled", true),
                Map.of("id", "ready-for-maintainer-review", "enabled", true)));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Policy.parse(duplicate));
        Map<String, Object> missingEnabled = new LinkedHashMap<>(base);
        missingEnabled.put("rules", List.of(Map.of("id", "ready-for-maintainer-review")));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Policy.parse(missingEnabled));
        thresholds.put("max_open_prs", -1);
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Policy.parse(base));
        thresholds.put("max_open_prs", "one");
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Policy.parse(base));
        thresholds.put("max_open_prs", 1);
        thresholds.put("external_associations", List.of());
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Policy.parse(base));
    }

    @Test
    void parserCoversOptionalAndMissingFacts(@TempDir Path dir) throws Exception {
        Map<String, Object> missingOpen = pr(29, Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> missingOpenAuthor = (Map<String, Object>) missingOpen.get("author");
        missingOpenAuthor.remove("open_prs");
        Map<String, Object> noTitles = pr(30, Map.of());
        noTitles.remove("title");
        noTitles.remove("body");
        noTitles.remove("labels");
        Path input = write(dir.resolve("mixed.json"), snapshot(List.of(missingOpen, noTitles)));
        Path policy = write(dir.resolve("policy.yaml"), policy(List.of("hold-open-pr-limit", "ready-for-maintainer-review")));
        Path json = dir.resolve("mixed.json.out");
        Path md = dir.resolve("mixed.md");
        assertEquals(0, Main.run(command(input, policy, json, md)));
        assertTrue(Files.readString(md).contains("author.open_prs"));
        String digest = TriageCommand.digestForAlgorithm("SHA-256", "x");
        assertTrue(digest.startsWith("sha256:"));
        assertEquals(71, digest.length());
        assertThrows(IllegalStateException.class, () -> TriageCommand.digestForAlgorithm("no-such-digest", "x"));
        assertThrows(IllegalArgumentException.class, () -> TriageCommand.Args.parse(new String[]{"replay", "--input", "x", "--policy", "y",
                "--output-json", "z", "--output-markdown", "m", "--as-of", "a", "--as-of", "b"}));
    }
}
