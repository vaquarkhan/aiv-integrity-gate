/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.invariant;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.util.FileExtensions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Generic invariant checks that should never reach mainline code.
 *
 * @author Vaquar Khan
 */
public final class InvariantGate implements QualityGate {
    private static final Pattern MERGE_CONFLICT_MARKER = Pattern.compile("(?m)^(<<<<<<<|=======|>>>>>>>)\\s");
    private static final Pattern PLACEHOLDER_MARKER = Pattern.compile("(?i)\\b(TBD|FIXME|XXX)\\b");

    /**
     * High-precision "AI edit-artifact" tells: elision placeholders and assistant chatter that a human
     * essentially never commits into source (e.g. a partial LLM paste). Applied to code files only, so
     * prose in Markdown/RST is not flagged. Deterministic, near-zero false positives on real code.
     */
    private static final Pattern AI_EDIT_ARTIFACT = Pattern.compile(
            "(?im)("
            + "\\.\\.\\.\\s*(existing|rest of|remaining)\\b[^\\n]*\\b(code|implementation|unchanged|methods?|functions?|here)"
            + "|\\b(rest|remainder) of (the )?(code|file|implementation|method|function|class)s?\\b[^\\n]*\\b(unchanged|the same|omitted|here)"
            + "|\\bas an ai language model\\b"
            + "|\\bhere('?s| is) the (updated|complete|fixed|full|corrected|revised) (code|implementation|file|version)\\b"
            + "|\\bin a real[- ]world scenario\\b"
            + "|<<<<<<< SEARCH|>>>>>>> REPLACE"
            + "|\\b(YOUR_CODE_HERE|INSERT_YOUR_CODE|INSERT_CODE_HERE)\\b"
            + ")");

    /**
     * Provenance trailers accidentally pasted into source (Apache {@code Generated-by:} / AI co-author lines).
     * Disclosure in commit messages or PR templates is fine; trailers inside product files are not.
     */
    private static final Pattern AI_PROVENANCE_TRAILER = Pattern.compile(
            "(?im)("
            + "^\\s*Generated-by:\\s*\\S+"
            + "|^\\s*Co-Authored-By:\\s*.*(?:noreply@anthropic\\.com|chatgpt|openai|copilot|cursor|gemini|claude)"
            + ")");

    /**
     * Empty / tautology tests that verify nothing (common agent dump). Test paths only; added-lines scoped.
     */
    private static final Pattern PLACEHOLDER_TEST = Pattern.compile(
            "(?im)("
            + "expect\\s*\\(\\s*true\\s*\\)\\s*\\.\\s*toBe\\s*\\(\\s*true\\s*\\)"
            + "|expect\\s*\\(\\s*true\\s*\\)\\s*\\.\\s*toBeTruthy\\s*\\(\\s*\\)"
            + "|assertTrue\\s*\\(\\s*true\\s*\\)"
            + "|assertEquals\\s*\\(\\s*true\\s*,\\s*true\\s*\\)"
            + "|assert\\s+True\\b"
            + "|self\\.assertTrue\\s*\\(\\s*True\\s*\\)"
            + ")");

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs", ".kt", ".kts",
            ".scala", ".c", ".cpp", ".cc", ".h", ".hpp", ".rb", ".sh", ".bash");

    /**
     * Per-repository settings read from the gate's {@code config} map.
     *
     * <pre>
     * - id: invariant
     *   config:
     *     placeholder_words: [TBD, FIXME, XXX, HACK]     # words that count as placeholders
     *     disabled_rules: [placeholder]                   # rule suffixes to skip entirely
     *     extra_artifact_patterns:                        # project-specific regexes, code files only
     *       - "TODO: implement actual logic"
     *     file_extensions: [.java, .py]                   # which files count as code (defaults shown above)
     * </pre>
     */
    record Settings(Pattern placeholder, Set<String> disabled, List<Pattern> extraArtifacts, Set<String> codeExtensions) {

        static Settings from(Map<String, Object> cfg) {
            List<String> words = stringList(cfg.get("placeholder_words"));
            Pattern placeholder = words.isEmpty() ? PLACEHOLDER_MARKER
                    : Pattern.compile("(?i)\\b(" + words.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")\\b");
            Set<String> disabled = new HashSet<>();
            for (String d : stringList(cfg.get("disabled_rules"))) {
                disabled.add(d.toLowerCase(Locale.ROOT).replace("invariant.", ""));
            }
            List<Pattern> extras = new ArrayList<>();
            for (String regex : stringList(cfg.get("extra_artifact_patterns"))) {
                try {
                    extras.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("invariant.extra_artifact_patterns: invalid regex '" + regex + "': " + e.getDescription(), e);
                }
            }
            Set<String> exts = cfg.containsKey("file_extensions") || cfg.containsKey("languages")
                    ? FileExtensions.fromConfig(cfg) : CODE_EXTENSIONS;
            return new Settings(placeholder, disabled, extras, exts);
        }

        boolean enabled(String rule) {
            return !disabled.contains(rule);
        }
    }

    static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
        }
        return out;
    }

    private static boolean isCodeFile(String path, Set<String> extensions) {
        return FileExtensions.matches(path, extensions);
    }

    static boolean isTestPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String lower = path.replace('\\', '/').toLowerCase();
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.contains("/__tests__/")
                || name.startsWith("test_")
                || name.endsWith("_test.py")
                || name.endsWith("_test.go")
                || name.endsWith("test.java")
                || name.endsWith("tests.java")
                || name.endsWith(".test.js")
                || name.endsWith(".test.ts")
                || name.endsWith(".test.jsx")
                || name.endsWith(".test.tsx")
                || name.endsWith(".spec.js")
                || name.endsWith(".spec.ts");
    }

    /** True when pattern hits on ADDED files, or on added lines of MODIFIED files (unslop-style). */
    private static boolean matchesOnAddedSurface(
            AddedLineIndex added, ChangedFile f, java.util.regex.Pattern pattern) {
        String content = f.getContent();
        if (f.getChangeType() == ChangedFile.ChangeType.ADDED) {
            return pattern.matcher(content).find();
        }
        return added.markerOnAddedLine(f.getPath(), content, pattern);
    }

    @Override
    public String getId() {
        return "invariant";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        List<String> failures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        AddedLineIndex added = AddedLineIndex.fromRawDiff(context.getDiff().getRawDiff());
        Settings s = Settings.from(context.getConfig().findGate(getId()).map(g -> g.getConfig()).orElse(Map.of()));

        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            String content = f.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (s.enabled("merge-conflict") && MERGE_CONFLICT_MARKER.matcher(content).find()) {
                String msg = "Merge conflict marker found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.merge-conflict", f.getPath(), 1, msg));
            }
            // Placeholders / AI artifacts: added-lines only when rawDiff is available, which avoids
            // false positives on pre-existing FIXME or legacy comments in touched files.
            if (s.enabled("placeholder") && matchesOnAddedSurface(added, f, s.placeholder())) {
                String msg = "Placeholder marker found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.placeholder", f.getPath(), 1, msg));
            }
            boolean code = isCodeFile(f.getPath(), s.codeExtensions());
            if (s.enabled("ai-edit-artifact") && code && matchesOnAddedSurface(added, f, AI_EDIT_ARTIFACT)) {
                String msg = "AI edit-artifact / elision marker found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.ai-edit-artifact", f.getPath(), 1, msg));
            }
            if (code) {
                for (Pattern extra : s.extraArtifacts()) {
                    if (matchesOnAddedSurface(added, f, extra)) {
                        String msg = "Project artifact pattern '" + extra.pattern() + "' found in " + f.getPath();
                        failures.add(msg);
                        findings.add(Finding.atLine("invariant.custom-artifact", f.getPath(), 1, msg));
                    }
                }
            }
            if (s.enabled("ai-provenance") && matchesOnAddedSurface(added, f, AI_PROVENANCE_TRAILER)) {
                String msg = "AI provenance trailer pasted into " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.ai-provenance", f.getPath(), 1, msg));
            }
            if (s.enabled("placeholder-test") && isTestPath(f.getPath()) && matchesOnAddedSurface(added, f, PLACEHOLDER_TEST)) {
                String msg = "Placeholder / tautology test found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.placeholder-test", f.getPath(), 1, msg));
            }
        }
        if (failures.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("\n", failures), findings);
    }
}
