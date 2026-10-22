/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.testadequacy;

import io.github.vaquarkhan.aiv.model.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds test functions introduced by the change whose body contains no assertion-like construct.
 *
 * <p>Research motivation: LLM-generated test suites are frequently superficial and pass while
 * discriminating nothing (SWE-Mutation; STING finds surviving mutants in 77% of SWE-bench Verified
 * instances). A test with no assertion, expectation, verification or exception check is the
 * cheapest deterministic proxy for that failure class. The scan is scoped to test functions whose
 * signature line was added by the diff, so pre-existing tests are never re-flagged.
 */
final class AssertionScanner {

    static final String RULE = "test-adequacy.assertion-free-test";

    private static final Pattern ASSERTION = Pattern.compile(
            "(?i)(assert|expect|verify|should|raises|throws|\\bfail\\s*\\(|snapshot|rejects|resolves"
                    + "|\\bto[A-Z]\\w*\\s*\\(|\\bcheck\\w*\\s*\\(|\\bok\\s*\\(|\\bequal\\w*\\s*\\(|\\bstrictEqual"
                    + "|\\bdeepEqual|\\bmatch\\w*\\s*\\(|\\bapprove\\w*\\s*\\(|\\bpanic\\s*\\(|\\bt\\.(error|fatal)\\w*\\s*\\()");

    private static final Pattern JAVA_TEST_ANNOTATION = Pattern.compile(
            "^\\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern PY_TEST_DEF = Pattern.compile("^(\\s*)(async\\s+)?def\\s+test\\w*\\s*\\(");
    private static final Pattern JS_TEST_CALL = Pattern.compile("^\\s*(it|test)(\\.only|\\.each\\([^)]*\\))?\\s*\\(");
    private static final Pattern GO_TEST_FUNC = Pattern.compile("^func\\s+Test\\w*\\s*\\(");

    private AssertionScanner() {
    }

    static List<Finding> scan(String path, String content, boolean fileIsAdded, AddedLines added) {
        List<Finding> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        String lower = TestPaths.norm(path).toLowerCase(Locale.ROOT);
        String[] lines = content.split("\n", -1);
        if (lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".scala")) {
            scanJava(path, lines, fileIsAdded, added, out);
        } else if (lower.endsWith(".py")) {
            scanPython(path, lines, fileIsAdded, added, out);
        } else if (lower.endsWith(".go")) {
            scanBraceLanguage(path, lines, GO_TEST_FUNC, fileIsAdded, added, out);
        } else {
            scanBraceLanguage(path, lines, JS_TEST_CALL, fileIsAdded, added, out);
        }
        return out;
    }

    private static void scanJava(String path, String[] lines, boolean fileIsAdded, AddedLines added,
                                 List<Finding> out) {
        for (int i = 0; i < lines.length; i++) {
            if (!JAVA_TEST_ANNOTATION.matcher(lines[i]).find()) {
                continue;
            }
            // Signature = first following line that is not another annotation.
            int sig = i + 1;
            while (sig < lines.length && lines[sig].trim().startsWith("@")) {
                sig++;
            }
            if (sig >= lines.length) {
                break;
            }
            if (!added.isAdded(path, lines[sig], fileIsAdded)) {
                continue;
            }
            String body = braceBody(lines, sig);
            if (body == null) {
                continue;
            }
            // @Test(expected = X.class) counts as an exception assertion; include the annotation line.
            String scope = lines[i] + "\n" + body;
            if (!ASSERTION.matcher(scope).find()) {
                out.add(finding(path, sig + 1, lines[sig]));
            }
        }
    }

    private static void scanPython(String path, String[] lines, boolean fileIsAdded, AddedLines added,
                                   List<Finding> out) {
        for (int i = 0; i < lines.length; i++) {
            Matcher m = PY_TEST_DEF.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            if (!added.isAdded(path, lines[i], fileIsAdded)) {
                continue;
            }
            int indent = m.group(1).length();
            StringBuilder body = new StringBuilder(lines[i]).append('\n');
            int j = i + 1;
            // Multi-line signature: consume until the parameter list closes, so the "):" line at the
            // def's own indentation is not mistaken for the end of the body.
            int depth = parenDepth(lines[i]);
            while (depth > 0 && j < lines.length) {
                body.append(lines[j]).append('\n');
                depth += parenDepth(lines[j]);
                j++;
            }
            while (j < lines.length) {
                String l = lines[j];
                if (!l.isBlank() && leadingSpaces(l) <= indent) {
                    break;
                }
                body.append(l).append('\n');
                j++;
            }
            if (!ASSERTION.matcher(body).find()) {
                out.add(finding(path, i + 1, lines[i]));
            }
        }
    }

    private static void scanBraceLanguage(String path, String[] lines, Pattern start, boolean fileIsAdded,
                                          AddedLines added, List<Finding> out) {
        for (int i = 0; i < lines.length; i++) {
            if (!start.matcher(lines[i]).find()) {
                continue;
            }
            if (!added.isAdded(path, lines[i], fileIsAdded)) {
                continue;
            }
            String body = braceBody(lines, i);
            if (body != null && !ASSERTION.matcher(body).find()) {
                out.add(finding(path, i + 1, lines[i]));
            }
        }
    }

    /**
     * Text from the first {@code '{'} found on lines {@code [start, start+3)} to its matching {@code '}'}
     * (or EOF). Returns null when no opening brace is near the start line (abstract / interface / one-liner).
     */
    static String braceBody(String[] lines, int start) {
        int depth = 0;
        boolean opened = false;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (!opened && i >= start + 3) {
                return null;
            }
            String line = lines[i];
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                }
                if (opened) {
                    sb.append(ch);
                }
                if (opened && depth == 0) {
                    return sb.toString();
                }
            }
            if (opened) {
                sb.append('\n');
            }
        }
        return opened ? sb.toString() : null;
    }

    /** Net change in parenthesis depth contributed by {@code s} (opening minus closing). */
    static int parenDepth(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                d++;
            } else if (c == ')') {
                d--;
            }
        }
        return d;
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    private static Finding finding(String path, int line, String sig) {
        return Finding.atLine(RULE, path, line,
                "Test added without any assertion/expectation/verification: '" + sig.trim()
                        + "' (a test that cannot fail proves nothing; add an assertion or remove it)");
    }
}
