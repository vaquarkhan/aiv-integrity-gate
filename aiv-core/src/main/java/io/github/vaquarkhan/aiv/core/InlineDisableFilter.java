/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suppresses findings using in-source comments (ESLint-style).
 * <pre>
 * // aiv-disable-next-line
 * // aiv-disable-next-line invariant.placeholder
 * # aiv-disable invariant.ai-edit-artifact
 * code(); // aiv-disable-line security.aws-key
 * </pre>
 * File-level {@code aiv-disable} (optional rule id) suppresses matching findings for that path.
 * Next-line / same-line directives use the finding's {@code startLine}.
 */
public final class InlineDisableFilter {

    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?i)(?:^|\\s)(?:(?://|#|--)|/\\*|\\*)\\s*"
                    + "aiv-disable(?:-(next-line|line))?"
                    + "(?:\\s+([\\w.*,-]+))?\\s*(?:\\*/)?\\s*$");

    private InlineDisableFilter() {
    }

    public static GateResult apply(GateResult raw, AIVContext context) {
        if (raw == null || raw.isPassed() || context == null) {
            return raw;
        }
        List<Finding> findings = raw.getFindings();
        if (findings.isEmpty()) {
            return raw;
        }
        Map<String, String> contentByPath = indexContents(context);
        List<Finding> kept = new ArrayList<>();
        for (Finding f : findings) {
            if (!isSuppressed(f, contentByPath, context.getWorkspace())) {
                kept.add(f);
            }
        }
        if (kept.isEmpty()) {
            return GateResult.pass(raw.getGateId());
        }
        if (kept.size() == findings.size()) {
            return raw;
        }
        String msg = String.join("\n", kept.stream().map(Finding::getMessage).toList());
        if (raw.blocksCi()) {
            return GateResult.fail(raw.getGateId(), msg, kept);
        }
        return GateResult.advisory(raw.getGateId(), msg, kept);
    }

    static boolean isSuppressed(Finding f, Map<String, String> contentByPath, Path workspace) {
        String path = BaselineFilter.norm(f.getFilePath());
        String content = contentByPath.get(path);
        if (content == null && workspace != null) {
            content = readWorkspace(workspace, path);
        }
        if (content == null || content.isBlank()) {
            return false;
        }
        String[] lines = content.split("\n", -1);
        Set<String> fileDisable = new HashSet<>();
        Map<Integer, Set<String>> nextLine = new HashMap<>();
        Map<Integer, Set<String>> sameLine = new HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = DIRECTIVE.matcher(lines[i].trim());
            if (!m.find()) {
                // also allow trailing comment forms
                m = DIRECTIVE.matcher(lines[i]);
                if (!m.find()) {
                    continue;
                }
            }
            String kind = m.group(1); // null | next-line | line
            Set<String> rules = parseRules(m.group(2));
            if (kind == null) {
                fileDisable.addAll(rules);
            } else if ("next-line".equalsIgnoreCase(kind)) {
                nextLine.computeIfAbsent(i + 2, k -> new HashSet<>()).addAll(rules);
            } else if ("line".equalsIgnoreCase(kind)) {
                sameLine.computeIfAbsent(i + 1, k -> new HashSet<>()).addAll(rules);
            }
        }
        if (matchesRules(fileDisable, f.getRuleId())) {
            return true;
        }
        int line = f.getStartLine();
        if (matchesRules(nextLine.getOrDefault(line, Set.of()), f.getRuleId())) {
            return true;
        }
        return matchesRules(sameLine.getOrDefault(line, Set.of()), f.getRuleId());
    }

    private static Set<String> parseRules(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            out.add("*");
            return out;
        }
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            out.add("*");
        }
        return out;
    }

    private static boolean matchesRules(Set<String> rules, String ruleId) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        if (rules.contains("*")) {
            return true;
        }
        String id = ruleId == null ? "" : ruleId;
        for (String r : rules) {
            if (r.equalsIgnoreCase(id)) {
                return true;
            }
            if (r.endsWith(".*")) {
                String prefix = r.substring(0, r.length() - 1);
                if (id.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> indexContents(AIVContext context) {
        Map<String, String> map = new HashMap<>();
        if (context.getDiff() == null || context.getDiff().getChangedFiles() == null) {
            return map;
        }
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            if (f.getContent() != null) {
                map.put(BaselineFilter.norm(f.getPath()), f.getContent());
            }
        }
        return map;
    }

    private static String readWorkspace(Path workspace, String relative) {
        try {
            Path p = workspace.resolve(relative).normalize();
            if (!p.startsWith(workspace.toAbsolutePath().normalize()) && !p.startsWith(workspace.normalize())) {
                return null;
            }
            if (!Files.isRegularFile(p)) {
                return null;
            }
            return CONTENT_LOADER.load(p);
        } catch (Exception e) {
            return null;
        }
    }

    @FunctionalInterface
    interface ContentLoader {
        String load(Path path) throws Exception;
    }

    /** Package-private for tests (IO failure path). */
    static ContentLoader CONTENT_LOADER = path -> Files.readString(path, StandardCharsets.UTF_8);
}
