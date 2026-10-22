/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies compiled {@link Rule}s to one file. Pure functions, no I/O. */
final class RuleEngine {

    /** One hit: rule, 1-based line, rendered message. */
    record Hit(Rule rule, int line, String message) {
    }

    private static final Pattern HOLLOW_LINE = Pattern.compile(
            "^\\s*(pass|return(\\s+(None|null|nil|0|false|\"\"|''))?;?|\\.\\.\\.|raise\\s+NotImplementedError(\\s*\\(.*\\))?"
                    + "|throw\\s+new\\s+(UnsupportedOperationException|NotImplementedException|Error)\\s*\\(.*\\);?"
                    + "|panic\\(.*\\)|//.*|#.*|/\\*.*\\*/|\\{|\\})\\s*(#.*|//.*)?$");

    private RuleEngine() {
    }

    static List<Hit> scan(String path, String content, Set<String> addedOnly, List<Rule> rules) {
        List<Hit> hits = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        for (Rule r : rules) {
            if (!r.appliesTo(path) || (r.fileFilter() != null && !r.fileFilter().matcher(content).find())) {
                continue;
            }
            if (r.allowed() != null && r.allowed().isEmpty() && r.skipWhenAllowedEmpty()) {
                continue;
            }
            Set<String> added = r.addedLinesOnly() ? addedOnly : null;
            for (int i = 0; i < lines.length; i++) {
                if (added != null && !added.contains(lines[i].trim())) {
                    continue;
                }
                switch (r.kind()) {
                    case "pattern" -> patternRule(r, lines[i], i + 1, hits);
                    case "call" -> callRule(r, lines, i, hits);
                    default -> hollowRule(r, lines, i, path, hits);
                }
            }
        }
        return hits;
    }

    private static void patternRule(Rule r, String line, int ln, List<Hit> hits) {
        Matcher m = r.pattern().matcher(line);
        while (m.find()) {
            String value = m.groupCount() == 0 ? m.group() : group(m, r.captureGroup() >= 0 ? r.captureGroup() : 1);
            if (value == null) {
                value = m.group();
            }
            if (valuePasses(r, value)) {
                hits.add(new Hit(r, ln, render(r.message(), value)));
            }
            if (m.end() == m.start()) {
                break;
            }
        }
    }

    private static void callRule(Rule r, String[] lines, int i, List<Hit> hits) {
        Matcher m = Pattern.compile(r.call().pattern() + "\\s*\\(").matcher(lines[i]);
        if (!m.find()) {
            return;
        }
        String call = callText(lines, i, m.start());
        if (call == null) {
            return;
        }
        if (r.ifArg() != null && !hasArg(call, r.ifArg())) {
            return;
        }
        if (r.requireArg() != null) {
            if (!hasArg(call, r.requireArg())) {
                hits.add(new Hit(r, i + 1, render(r.message(), m.group().replaceAll("\\s*\\($", ""))));
            }
            return;
        }
        Matcher c = r.capture().matcher(call);
        if (!c.find()) {
            return;
        }
        String value = firstGroup(c);
        if (valuePasses(r, value)) {
            hits.add(new Hit(r, i + 1, render(r.message(), value)));
        }
    }

    private static void hollowRule(Rule r, String[] lines, int i, String path, List<Hit> hits) {
        String line = lines[i];
        boolean python = path.toLowerCase(Locale.ROOT).endsWith(".py");
        Pattern def = python
                ? Pattern.compile("^(\\s*)(async\\s+)?def\\s+(" + r.function().pattern() + ")\\s*\\(.*\\)\\s*(->\\s*[^:]+)?:\\s*(#.*)?$")
                : Pattern.compile("^(\\s*)[\\w<>\\[\\],\\s]*?\\b(" + r.function().pattern() + ")\\s*\\([^)]*\\)[^{;]*\\{\\s*$");
        Matcher m = def.matcher(line);
        if (!m.find()) {
            return;
        }
        boolean hollow = python ? hollowIndent(lines, i, m.group(1).length()) : hollowBraces(lines, i);
        if (hollow) {
            hits.add(new Hit(r, i + 1, render(r.message(), m.group(python ? 3 : 2))));
        }
    }

    static boolean valuePasses(Rule r, String value) {
        if (r.captureMatches() != null && !r.captureMatches().matcher(value).find()) {
            return false;
        }
        return r.allowed() == null || !r.allowed().contains(value);
    }

    static boolean hasArg(String call, String arg) {
        return Pattern.compile("(?s).*\\b" + Pattern.quote(arg) + "\\s*=.*").matcher(call).matches();
    }

    static String firstGroup(Matcher m) {
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) {
                return m.group(g);
            }
        }
        return m.group();
    }

    private static String group(Matcher m, int g) {
        return g <= m.groupCount() ? m.group(g) : null;
    }

    static String render(String template, String value) {
        return template.replace("{value}", value == null ? "" : value);
    }

    /** Text of the call starting at {@code col} on line {@code i} through its matching ')' (max 200 lines), or null. */
    static String callText(String[] lines, int i, int col) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean opened = false;
        for (int l = i; l < lines.length && l < i + 200; l++) {
            String text = l == i ? lines[l].substring(col) : lines[l];
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                sb.append(ch);
                if (ch == '(') {
                    depth++;
                    opened = true;
                } else if (ch == ')') {
                    depth--;
                    if (opened && depth == 0) {
                        return sb.toString();
                    }
                }
            }
            sb.append('\n');
        }
        return null;
    }

    /** Python: every non-blank, non-comment, non-docstring body line is a no-op statement. */
    static boolean hollowIndent(String[] lines, int defIndex, int defIndent) {
        boolean sawStatement = false;
        boolean inDocstring = false;
        for (int l = defIndex + 1; l < lines.length; l++) {
            String line = lines[l];
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (indent <= defIndent) {
                break;
            }
            if (inDocstring) {
                if (t.contains("\"\"\"") || t.contains("'''")) {
                    inDocstring = false;
                }
                continue;
            }
            if (t.startsWith("\"\"\"") || t.startsWith("'''")) {
                String q = t.substring(0, 3);
                if (t.length() == 3 || !t.substring(3).contains(q)) {
                    inDocstring = true;
                }
                continue;
            }
            if (t.startsWith("#")) {
                continue;
            }
            if (!HOLLOW_LINE.matcher(line).matches()) {
                return false;
            }
            sawStatement = true;
        }
        return sawStatement;
    }

    /** Brace languages: from the line that opens the body to its matching '}', only no-op statements. */
    static boolean hollowBraces(String[] lines, int defIndex) {
        int depth = 1;
        boolean sawStatement = false;
        for (int l = defIndex + 1; l < lines.length; l++) {
            String t = lines[l].trim();
            if (t.isEmpty()) {
                continue;
            }
            for (char ch : t.toCharArray()) {
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if (depth <= 0) {
                return sawStatement;
            }
            if (!HOLLOW_LINE.matcher(lines[l]).matches()) {
                return false;
            }
            if (!t.startsWith("//") && !t.startsWith("#") && !t.startsWith("/*")) {
                sawStatement = true;
            }
        }
        return false;
    }
}
