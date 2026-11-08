/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assigns each changed path one category and one area. Project globs for a category are checked
 * before the built-in globs; categories are tried in {@link #CATEGORIES} order and the first match
 * wins. Areas come from the configured map in order, then the first {@code areaDepth} directories.
 *
 * @author Vaquar Khan
 */
final class Classifier {

    static final String SOURCE = "source";

    /** Category order matters: lockfiles before config, tests before docs, docs before config. */
    static final Map<String, List<String>> DEFAULTS = defaults();
    static final Set<String> CATEGORIES = categories();
    static final List<String> DEFAULT_NON_REVIEWABLE = List.of("lockfile", "generated", "vendored", "binary");

    private final Map<String, List<Pattern>> categories = new LinkedHashMap<>();
    private final Map<String, List<Pattern>> areas = new LinkedHashMap<>();
    private final int areaDepth;

    Classifier(Map<String, List<String>> projectCategories, Map<String, List<String>> areaGlobs, int areaDepth) {
        for (Map.Entry<String, List<String>> e : DEFAULTS.entrySet()) {
            List<String> globs = new ArrayList<>(projectCategories.getOrDefault(e.getKey(), List.of()));
            globs.addAll(e.getValue());
            categories.put(e.getKey(), Glob.compileAll(globs));
        }
        areaGlobs.forEach((name, globs) -> areas.put(name, Glob.compileAll(globs)));
        this.areaDepth = areaDepth;
    }

    String category(String path) {
        for (Map.Entry<String, List<Pattern>> e : categories.entrySet()) {
            if (Glob.any(path, e.getValue())) {
                return e.getKey();
            }
        }
        return SOURCE;
    }

    String area(String path) {
        for (Map.Entry<String, List<Pattern>> e : areas.entrySet()) {
            if (Glob.any(path, e.getValue())) {
                return e.getKey();
            }
        }
        String[] parts = path.split("/");
        if (parts.length == 1) {
            return "(root)";
        }
        return String.join("/", java.util.Arrays.copyOfRange(parts, 0, Math.min(areaDepth, parts.length - 1)));
    }

    private static Map<String, List<String>> defaults() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("lockfile", List.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "uv.lock", "poetry.lock",
                "Cargo.lock", "go.sum", "Gemfile.lock", "composer.lock", "*.lock"));
        m.put("generated", List.of("**/generated/**", "*_pb2.py", "*_pb2_grpc.py", "*.min.js", "*.min.css",
                "*.snap", "**/dist/**"));
        m.put("vendored", List.of("vendor/**", "**/vendor/**", "third_party/**", "**/third_party/**",
                "thirdparty/**"));
        m.put("binary", List.of("*.png", "*.jpg", "*.jpeg", "*.gif", "*.ico", "*.pdf", "*.jar", "*.zip", "*.gz",
                "*.woff", "*.woff2", "*.ttf", "*.so", "*.dll", "*.exe"));
        m.put("ci", List.of(".github/workflows/**", ".github/actions/**", ".gitlab-ci.yml", ".circleci/**",
                "Jenkinsfile", "azure-pipelines.yml", ".buildkite/**"));
        m.put("build-infra", List.of());
        m.put("ai-instruction", List.of("AGENTS.md", "CLAUDE.md", "GEMINI.md", ".github/copilot-instructions.md",
                ".github/instructions/**", ".cursor/**", ".cursorrules", ".kiro/**", ".github/skills/**"));
        m.put("migration", List.of("**/migrations/**", "**/alembic/**"));
        m.put("dependency-manifest", List.of("requirements*.txt", "pyproject.toml", "setup.py", "setup.cfg",
                "package.json", "pom.xml", "build.gradle", "build.gradle.kts", "Cargo.toml", "go.mod", "Gemfile",
                "*.csproj"));
        m.put("test", List.of("**/test/**", "**/tests/**", "**/__tests__/**", "**/testing/**", "**/testdata/**",
                "test_*.py", "*_test.py", "conftest.py", "*_test.go", "*Test.java", "*Tests.java", "*IT.java",
                "*Test.scala", "*Suite.scala", "*Spec.scala", "*Test.kt", "*_spec.rb", "*_test.rb",
                "*.test.*", "*.spec.*"));
        m.put("docs", List.of("*.md", "*.rst", "*.adoc", "*.txt", "docs/**", "**/docs/**", "**/newsfragments/**",
                "CHANGELOG*", "NEWS*"));
        m.put("config", List.of("*.yaml", "*.yml", "*.json", "*.toml", "*.ini", "*.cfg", "*.properties",
                "*.conf", "*.xml"));
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Set<String> categories() {
        Set<String> s = new java.util.LinkedHashSet<>(DEFAULTS.keySet());
        s.add(SOURCE);
        return java.util.Collections.unmodifiableSet(s);
    }
}
