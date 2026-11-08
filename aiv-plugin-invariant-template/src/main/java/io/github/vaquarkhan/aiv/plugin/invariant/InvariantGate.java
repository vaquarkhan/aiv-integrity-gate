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
    private static final Pattern MERGE_CONFLICT_BLOCK = Pattern.compile(
            "(?ms)^<<<<<<<[^\\n]*\\n.*?^=======[^\\n]*\\n.*?^>>>>>>>[^\\n]*(?:$|\\n)");
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

    /**
     * Invisible characters used to hide instructions from human reviewers (Pillar Security "Rules File
     * Backdoor", Trojan Source). Always suspicious: zero-width space, word joiner and invisible operators,
     * bidi embeddings/overrides/isolates, Unicode tag characters, and a BOM anywhere but the start of a line.
     * Context dependent: ZWNJ, ZWJ, LRM and RLM are legitimate in emoji sequences and right-to-left or
     * Indic text, so they only count when glued to an ASCII letter or digit (keyword splitting).
     */
    static final Pattern HIDDEN_UNICODE = Pattern.compile(
            "[\\u200B\\u2060-\\u2064\\u202A-\\u202E\\u2066-\\u2069\\x{E0000}-\\x{E007F}]"
            + "|(?<!^)\\uFEFF"
            + "|[A-Za-z0-9][\\u200C-\\u200F]|[\\u200C-\\u200F][A-Za-z0-9]");

    /** Built-in instruction surfaces used when {@code hidden_unicode} is on but no paths are configured. */
    static final List<String> DEFAULT_INSTRUCTION_PATHS = List.of(
            ".github/copilot-instructions.md", ".github/instructions/**", ".github/skills/**",
            ".github/prompts/**", ".github/agents/**", ".cursor/rules/**", ".cursorrules",
            ".windsurf/rules/**", ".clinerules", ".kiro/steering/**", ".amazonq/rules/**",
            "**/AGENTS.md", "**/CLAUDE.md", "**/GEMINI.md", "**/REVIEW.md");

    /** Where {@code invariant.hidden-unicode} looks. */
    enum HiddenUnicodeScope {
        OFF, INSTRUCTION_FILES, ALL_FILES;

        static HiddenUnicodeScope from(Object value) {
            if (value == null) {
                return OFF;
            }
            String v = value.toString().trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "", "off", "false" -> OFF;
                case "instruction_files" -> INSTRUCTION_FILES;
                case "all_files", "true" -> ALL_FILES;
                default -> throw new IllegalArgumentException(
                        "invariant.hidden_unicode must be off, instruction_files or all_files, got '" + value + "'");
            };
        }
    }

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs", ".kt", ".kts",
            ".scala", ".c", ".cpp", ".cc", ".h", ".hpp", ".rb", ".sh", ".bash");
    private static final Set<String> CONFLICT_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs", ".kt", ".kts",
            ".scala", ".c", ".cpp", ".cc", ".h", ".hpp", ".rb", ".sh", ".bash",
            ".yaml", ".yml", ".json", ".xml", ".toml", ".ini", ".cfg", ".properties");

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
     *     review_instruction_paths:                       # opt-in: files that steer AI reviewers
     *       - .github/copilot-instructions.md             # exact path
     *       - .github/instructions/**                     # directory prefix
     *       - "**&#47;AGENTS.md"                              # file name at any depth
     *     hidden_unicode: instruction_files               # opt-in: off (default), instruction_files, all_files
     * </pre>
     */
    record Settings(Pattern placeholder, Set<String> disabled, List<Pattern> extraArtifacts,
                    Set<String> codeExtensions, Set<String> conflictExtensions, List<String> allowedGitlinks,
                    List<String> reviewInstructionPaths, HiddenUnicodeScope hiddenUnicode) {

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
            Set<String> conflictExts = cfg.containsKey("conflict_file_extensions")
                    ? extensionSet(cfg.get("conflict_file_extensions"), CONFLICT_EXTENSIONS) : CONFLICT_EXTENSIONS;
            return new Settings(placeholder, disabled, extras, exts, conflictExts,
                    stringList(cfg.get("allowed_gitlinks")), stringList(cfg.get("review_instruction_paths")),
                    HiddenUnicodeScope.from(cfg.get("hidden_unicode")));
        }

        /** Paths whose hidden Unicode is checked in {@code instruction_files} mode. */
        List<String> instructionPathsOrDefault() {
            return reviewInstructionPaths.isEmpty() ? DEFAULT_INSTRUCTION_PATHS : reviewInstructionPaths;
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

    private static Set<String> extensionSet(Object value, Set<String> fallback) {
        Set<String> result = stringList(value).stream()
                .map(s -> s.startsWith(".") ? s.toLowerCase(Locale.ROOT) : "." + s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return result.isEmpty() ? fallback : Set.copyOf(result);
    }

    private static boolean allowedGitlink(String path, List<String> patterns) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        for (String pattern : patterns) {
            String p = pattern.replace('\\', '/');
            if (p.equals(normalized)) {
                return true;
            }
            if (p.endsWith("/**") && normalized.startsWith(p.substring(0, p.length() - 3))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches a repository path against review-instruction patterns. Supported forms: an exact path,
     * a directory prefix ending in {@code /**}, and a file name at any depth written as {@code **}{@code /NAME}.
     * Matching is case-insensitive because instruction files such as {@code AGENTS.md} are looked up
     * case-insensitively by several reviewers.
     */
    static boolean matchesReviewInstructionPath(String path, List<String> patterns) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String p = pattern.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (p.startsWith("**/")) {
                String name = p.substring(3);
                if (normalized.equals(name) || normalized.endsWith("/" + name)) {
                    return true;
                }
            } else if (p.endsWith("/**")) {
                if (normalized.startsWith(p.substring(0, p.length() - 2))) {
                    return true;
                }
            } else if (p.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AI reviewers (Copilot code review, Claude, Gemini) read instructions and skills from the PR head
     * branch, so a PR that edits them can steer its own review. The fact is objective: the path changed.
     */
    private static void reviewInstructionChange(ChangedFile f, Settings s, List<String> failures, List<Finding> findings) {
        if (!s.enabled("review-instruction-change") || s.reviewInstructionPaths().isEmpty()) {
            return;
        }
        String touched = matchesReviewInstructionPath(f.getPath(), s.reviewInstructionPaths()) ? f.getPath()
                : matchesReviewInstructionPath(f.getOldPath(), s.reviewInstructionPaths()) ? f.getOldPath()
                : null;
        if (touched == null) {
            return;
        }
        String msg = "AI review instruction file " + touched + " changed in this PR ("
                + f.getChangeType().name().toLowerCase(Locale.ROOT)
                + "); AI reviewers read it from the head branch, so a maintainer must review it first";
        failures.add(msg);
        findings.add(Finding.atLine("invariant.review-instruction-change", f.getPath(), 1, msg));
    }

    private static void hiddenUnicode(AddedLineIndex added, ChangedFile f, Settings s,
                                      List<String> failures, List<Finding> findings) {
        if (!s.enabled("hidden-unicode") || f.getContent() == null || f.getContent().isEmpty()) {
            return;
        }
        if (matchesOnAddedSurface(added, f, HIDDEN_UNICODE)) {
            String msg = "Hidden Unicode (zero-width, bidi control or tag characters) added to " + f.getPath()
                    + "; such characters can hide instructions from human reviewers";
            failures.add(msg);
            findings.add(Finding.atLine("invariant.hidden-unicode", f.getPath(), 1, msg));
        }
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

        // exclude_paths is a noise filter (for example "*.md"); it must not hide a change to the files
        // AI reviewers take instructions from, so those excluded files are checked for this rule only.
        if (context.getMetadata().get(AIVContext.EXCLUDED_CHANGED_FILES) instanceof List<?> excluded) {
            for (Object o : excluded) {
                if (o instanceof ChangedFile f) {
                    reviewInstructionChange(f, s, failures, findings);
                    if (s.hiddenUnicode() != HiddenUnicodeScope.OFF
                            && matchesReviewInstructionPath(f.getPath(), s.instructionPathsOrDefault())) {
                        hiddenUnicode(added, f, s, failures, findings);
                    }
                }
            }
        }
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            if (s.enabled("unexpected-gitlink") && f.isNewGitlink()
                    && !allowedGitlink(f.getPath(), s.allowedGitlinks())) {
                String msg = "Unapproved Git gitlink introduced at " + f.getPath()
                        + " (mode " + f.getOldMode() + " -> " + f.getNewMode() + ")";
                failures.add(msg);
                findings.add(Finding.atLine("invariant.unexpected-gitlink", f.getPath(), 1, msg));
            }
            // Runs before the content check so deletions and renames are covered too.
            reviewInstructionChange(f, s, failures, findings);
            if (s.hiddenUnicode() == HiddenUnicodeScope.ALL_FILES
                    || (s.hiddenUnicode() == HiddenUnicodeScope.INSTRUCTION_FILES
                    && matchesReviewInstructionPath(f.getPath(), s.instructionPathsOrDefault()))) {
                hiddenUnicode(added, f, s, failures, findings);
            }
            String content = f.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            boolean conflictEligible = isCodeFile(f.getPath(), s.conflictExtensions());
            boolean coherentConflict = MERGE_CONFLICT_BLOCK.matcher(content).find();
            boolean conflictOnAddedSurface = matchesOnAddedSurface(added, f, MERGE_CONFLICT_MARKER);
            if (s.enabled("merge-conflict") && conflictEligible && coherentConflict && conflictOnAddedSurface) {
                String msg = "Unresolved merge conflict block found on the added surface of " + f.getPath();
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
