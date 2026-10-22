/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.testadequacy;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Path conventions for test files and the production file they are expected to cover. */
final class TestPaths {

    static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".js", ".ts", ".jsx", ".tsx", ".mjs", ".go", ".rs", ".rb", ".scala");

    private static final Pattern TEST_DIR = Pattern.compile(
            "(^|/)(test|tests|__tests__|spec|specs|testing|src/test|src/integrationTest)(/|$)");

    private static final Pattern TEST_BASENAME = Pattern.compile(
            "(?i)(^test_.*\\.py$)|(_test\\.(py|go|rb)$)|(^test.*\\.py$)"
                    + "|((Test|Tests|IT|TestCase|Spec)\\.(java|kt|scala)$)"
                    + "|(\\.(test|spec)\\.(js|ts|jsx|tsx|mjs)$)|(_spec\\.rb$)");

    private static final Set<String> NOT_PRODUCTION = Set.of(
            "__init__.py", "setup.py", "conf.py", "conftest.py", "module-info.java", "package-info.java",
            "index.ts", "index.js", "main.py", "__main__.py");

    private TestPaths() {
    }

    static String norm(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    static String basename(String path) {
        String p = norm(path);
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    static String stem(String basename) {
        int i = basename.lastIndexOf('.');
        return i > 0 ? basename.substring(0, i) : basename;
    }

    static boolean isCodeFile(String path) {
        String lower = norm(path).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".d.ts")) {
            return false;
        }
        for (String ext : CODE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    static boolean isTestPath(String path) {
        String p = norm(path);
        return TEST_DIR.matcher(p.toLowerCase(Locale.ROOT)).find() || TEST_BASENAME.matcher(basename(p)).find();
    }

    /** Production files worth asking "where is the test?" about. */
    static boolean isProductionCandidate(String path) {
        if (!isCodeFile(path) || isTestPath(path)) {
            return false;
        }
        String base = basename(path).toLowerCase(Locale.ROOT);
        if (NOT_PRODUCTION.contains(base)) {
            return false;
        }
        String lower = "/" + norm(path).toLowerCase(Locale.ROOT);
        return !lower.contains("/generated/") && !lower.contains("/examples/") && !lower.contains("/docs/")
                && !lower.contains("/scripts/") && !lower.contains("/benchmarks/") && !lower.contains("/demos/");
    }

    /** Lower-cased test-file stems that would conventionally cover {@code sourcePath}. */
    static Set<String> candidateTestStems(String sourcePath) {
        String stem = stem(basename(sourcePath)).toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        out.add(stem + "test");
        out.add(stem + "tests");
        out.add("test" + stem);
        out.add(stem + "it");
        out.add(stem + "spec");
        out.add(stem + "testcase");
        out.add("test_" + stem);
        out.add(stem + "_test");
        out.add(stem + "_spec");
        out.add(stem + ".test");
        out.add(stem + ".spec");
        return out;
    }
}
