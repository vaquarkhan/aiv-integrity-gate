/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Computes the fact snapshot for one change. Pure function of the diff, the PR metadata and read-only
 * git history; facts that cannot be computed in this run are left out (unknown).
 *
 * @author Vaquar Khan
 */
final class FactCollector {

    /** PR metadata supplied by CI; every field may be null in a local run. */
    record Meta(String title, String body, List<String> labels, String authorAssociation, Boolean draft) {
    }

    private static final Pattern CONVENTIONAL = Pattern.compile("^(feat|fix|chore|docs|refactor|test|tests|ci|build|perf|style)(\\([^)]*\\))?!?:");
    /** Function or class starts in added lines: Python, Go, JavaScript/TypeScript, Kotlin, Rust. */
    private static final Pattern UNIT_START = Pattern.compile(
            "^\\s*(?:(?:async\\s+)?def\\s+\\w+\\s*\\(|class\\s+\\w+|func\\s+(?:\\([^)]*\\)\\s*)?\\w+\\s*\\("
                    + "|(?:export\\s+)?(?:async\\s+)?function\\s+\\w+\\s*\\(|fun\\s+\\w+\\s*\\(|(?:pub\\s+)?fn\\s+\\w+\\s*[(<])");
    private static final Pattern FUNCTION_START = Pattern.compile(
            "^\\s*(?:(?:async\\s+)?def\\s|func\\s|(?:export\\s+)?(?:async\\s+)?function\\s|fun\\s|(?:pub\\s+)?fn\\s)");
    private static final Pattern STUB_BODY = Pattern.compile(
            "^\\s*(?:pass|\\.\\.\\.|raise\\s+NotImplementedError(?:\\(.*\\))?|throw\\s+new\\s+(?:Error|UnsupportedOperationException)\\(.*\\);?"
                    + "|todo!\\(.*\\)|unimplemented!\\(.*\\)|panic\\(\"not implemented\"\\))\\s*$");
    private static final Pattern DOC_OR_COMMENT = Pattern.compile("^\\s*(?:#|//|/\\*|\\*|\"\"\"|''')");
    static final int LARGE_NEW_FILE_LINES = 500;

    private FactCollector() {
    }

    static Map<String, Object> collect(Diff diff, List<ChangedFile> files, Meta meta, PolicyConfig cfg,
                                       GitHistory git, Path workspace) {
        Map<String, Object> f = new LinkedHashMap<>();
        Map<String, DiffStats.FileDiff> stats = DiffStats.parse(diff.getRawDiff());
        Map<String, Integer> byCategory = new TreeMap<>();
        Map<String, int[]> byArea = new TreeMap<>(); // files, lines, non-test lines
        Set<String> sourceAreas = new LinkedHashSet<>();
        Set<String> testAreas = new LinkedHashSet<>();
        int changed = 0, reviewable = 0, added = 0, deleted = 0, renamed = 0, wsOnly = 0;
        int lAdded = 0, lDeleted = 0, lSource = 0, lTest = 0, lDocs = 0, testFiles = 0, testFns = 0;
        int largeNewFiles = 0, maxFunction = 0, stubs = 0;
        for (ChangedFile cf : files) {
            String path = cf.getPath() == null ? "" : cf.getPath().replace('\\', '/');
            if (path.isBlank()) {
                continue;
            }
            changed++;
            DiffStats.FileDiff d = stats.getOrDefault(path, DiffStats.FileDiff.EMPTY);
            String cat = cfg.classifier().category(path);
            String area = cfg.classifier().area(path);
            byCategory.merge(cat, 1, Integer::sum);
            added += cf.getChangeType() == ChangedFile.ChangeType.ADDED ? 1 : 0;
            deleted += cf.getChangeType() == ChangedFile.ChangeType.DELETED ? 1 : 0;
            renamed += cf.getChangeType() == ChangedFile.ChangeType.RENAMED ? 1 : 0;
            boolean ws = d.whitespaceOnly();
            wsOnly += ws ? 1 : 0;
            if (cfg.nonReviewable().contains(cat) || ws || d.changed() == 0) {
                continue;
            }
            reviewable++;
            lAdded += d.added().size();
            lDeleted += d.removed().size();
            int[] a = byArea.computeIfAbsent(area, x -> new int[3]);
            a[0]++;
            a[1] += d.changed();
            if (cat.equals("test")) {
                testFiles++;
                lTest += d.changed();
                testFns += MetaText.testFunctions(d.added());
                testAreas.add(area);
            } else {
                a[2] += d.changed();
            }
            lSource += cat.equals(Classifier.SOURCE) ? d.changed() : 0;
            lDocs += cat.equals("docs") ? d.changed() : 0;
            if (cat.equals(Classifier.SOURCE)) {
                sourceAreas.add(area);
                largeNewFiles += cf.getChangeType() == ChangedFile.ChangeType.ADDED
                        && d.added().size() >= LARGE_NEW_FILE_LINES ? 1 : 0;
                int[] shape = codeShape(d.added());
                maxFunction = Math.max(maxFunction, shape[0]);
                stubs += shape[1];
            }
        }
        f.put("files.changed", changed);
        f.put("files.reviewable", reviewable);
        f.put("files.added", added);
        f.put("files.deleted", deleted);
        f.put("files.renamed", renamed);
        byCategory.forEach((k, v) -> f.put(Facts.BY_CATEGORY + k, v));
        byArea.forEach((k, v) -> f.put(Facts.BY_AREA + k, v[0]));
        f.put("lines.changed", lAdded + lDeleted);
        f.put("lines.added", lAdded);
        f.put("lines.deleted", lDeleted);
        f.put("lines.source_changed", lSource);
        f.put("lines.test_changed", lTest);
        f.put("lines.docs_changed", lDocs);
        f.put("lines.non_test_changed", lAdded + lDeleted - lTest);
        // numstat over every changed path, including generated and vendored files: the raw size of the diff
        f.put("lines.total_changed", Math.max(0, diff.getLinesAdded()) + Math.max(0, diff.getLinesDeleted()));
        f.put("code.large_new_files", largeNewFiles);
        f.put("code.max_function_lines", maxFunction);
        f.put("code.stub_functions", stubs);
        f.put("scope.areas", byArea.size());
        f.put("scope.area_list", String.join(", ", byArea.keySet()));
        f.put("churn.whitespace_only_files", wsOnly);
        f.put("tests.files_changed", testFiles);
        f.put("tests.functions_added", testFns);
        sourceAreas.removeAll(testAreas);
        f.put("tests.source_areas_without_tests", sourceAreas.size());
        String tier = meta.authorAssociation() == null ? null
                : cfg.associationTier().getOrDefault(meta.authorAssociation().toUpperCase(), "external");
        budget(f, cfg, tier, reviewable, lAdded + lDeleted, lAdded + lDeleted - lTest, byArea);
        metadata(f, meta, cfg, tier, testFns);
        if (cfg.gitFacts()) {
            history(f, git, workspace, diff.getBaseRef(), diff.getHeadRef());
        }
        return f;
    }

    /**
     * Shape of the added lines of one source file: the longest run of added lines that follows a function
     * start before the next function or class start, and the number of functions whose first statement is
     * a stub ({@code pass}, {@code ...}, {@code raise NotImplementedError}, {@code todo!()} and similar).
     * Added lines from separate hunks are treated as one sequence, so the length is an upper bound.
     */
    static int[] codeShape(List<String> added) {
        int longest = 0, current = -1, stubs = 0;
        for (int i = 0; i < added.size(); i++) {
            String line = added.get(i);
            if (UNIT_START.matcher(line).find()) {
                longest = Math.max(longest, current);
                current = FUNCTION_START.matcher(line).find() ? 0 : -1;
                if (current == 0 && stubFollows(added, i)) {
                    stubs++;
                }
            } else if (current >= 0) {
                current++;
            }
        }
        return new int[] {Math.max(longest, current), stubs};
    }

    private static boolean stubFollows(List<String> added, int def) {
        for (int j = def + 1; j < added.size(); j++) {
            String next = added.get(j);
            if (!next.isBlank() && !DOC_OR_COMMENT.matcher(next).find()) {
                return STUB_BODY.matcher(next).matches();
            }
        }
        return false;
    }

    private static void budget(Map<String, Object> f, PolicyConfig cfg, String tier, int files, int lines,
                               int nonTest, Map<String, int[]> byArea) {
        List<String> over = new ArrayList<>();
        String wholeKey = tier != null && cfg.budgets().containsKey(tier) ? tier : "default";
        check(over, "", wholeKey, cfg.budgets().get(wholeKey), files, lines, nonTest);
        for (Map.Entry<String, int[]> e : byArea.entrySet()) {
            String key = tier != null && cfg.budgets().containsKey(tier + "." + e.getKey()) ? tier + "." + e.getKey() : e.getKey();
            int[] a = e.getValue();
            check(over, e.getKey() + ": ", key, cfg.budgets().get(key), a[0], a[1], a[2]);
        }
        f.put("budget.exceeded", !over.isEmpty());
        f.put("budget.detail", over.isEmpty() ? "within budget" : String.join("; ", over));
    }

    private static void check(List<String> over, String prefix, String key, PolicyConfig.Limits l,
                              int files, int lines, int nonTest) {
        if (l == null) {
            return;
        }
        if (l.files() != null && files > l.files()) {
            over.add(prefix + files + " reviewable files > " + l.files() + " (" + key + ")");
        }
        if (l.lines() != null && lines > l.lines()) {
            over.add(prefix + lines + " changed lines > " + l.lines() + " (" + key + ")");
        }
        if (l.nonTestLines() != null && nonTest > l.nonTestLines()) {
            over.add(prefix + nonTest + " non-test lines > " + l.nonTestLines() + " (" + key + ")");
        }
    }

    private static void metadata(Map<String, Object> f, Meta meta, PolicyConfig cfg, String tier, int testFns) {
        if (meta.title() != null) {
            f.put("intent.class", MetaText.intent(meta.title()));
        }
        if (meta.labels() != null) {
            f.put("meta.labels", meta.labels());
        }
        if (tier != null) {
            f.put("meta.author_association", meta.authorAssociation().toUpperCase());
            f.put("meta.author_tier", tier);
        }
        if (meta.draft() != null) {
            f.put("meta.is_draft", meta.draft());
        }
        if (meta.body() == null) {
            return;
        }
        String body = MetaText.clean(meta.body(), cfg.templateIgnore());
        f.put("meta.linked_issue", MetaText.linkedIssue((meta.title() == null ? "" : meta.title()) + "\n" + body,
                cfg.linkedIssuePatterns()));
        f.put("meta.body_words", MetaText.words(body));
        List<String> missing = MetaText.missingSections(body, cfg.requiredSections());
        f.put("template.sections_missing", missing.size());
        f.put("template.missing_list", missing.isEmpty() ? "none missing" : String.join(", ", missing));
        int claims = MetaText.testsClaimCount(body);
        f.put("claims.tests_count", claims);
        f.put("claims.tests_overclaim", claims == 0 ? 0 : claims - testFns);
        f.put("claims.tests_added", MetaText.testsAddedClaim(body));
        f.put("claims.ai_disclosure_mismatch", MetaText.disclosureMismatch(body));
    }

    private static void history(Map<String, Object> f, GitHistory git, Path ws, String base, String head) {
        if (base == null || head == null || base.isBlank() || head.isBlank()) {
            return;
        }
        count(git.run(ws, List.of("rev-list", "--count", base + ".." + head))).ifPresent(n -> f.put("git.commits", n));
        count(git.run(ws, List.of("rev-list", "--count", head + ".." + base))).ifPresent(n -> f.put("git.behind_base", n));
        git.run(ws, List.of("log", "--format=%s%x1f%b%x1e", base + ".." + head)).ifPresent(log -> {
            int noBody = 0, conventional = 0;
            for (String entry : log.split("\u001e")) {
                String[] parts = entry.strip().split("\u001f", 2);
                if (parts[0].isBlank()) {
                    continue;
                }
                noBody += parts.length < 2 || parts[1].isBlank() ? 1 : 0;
                conventional += CONVENTIONAL.matcher(parts[0].strip()).find() ? 1 : 0;
            }
            f.put("git.commits_without_body", noBody);
            f.put("git.conventional_prefix_commits", conventional);
        });
    }

    private static Optional<Integer> count(Optional<String> out) {
        try {
            return out.map(s -> Integer.parseInt(s.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
