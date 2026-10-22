/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.pkgexist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts dependency declarations that a change <em>added</em> to a manifest.
 * Supports {@code requirements*.txt}, {@code pyproject.toml}, {@code package.json}, {@code pom.xml}.
 * Everything is regex-based and conservative: when a line cannot be read as a declaration it is ignored.
 */
final class DependencyDeclarations {

    /** One declared package: ecosystem (pypi/npm/maven), name, 1-based line in the manifest. */
    record Decl(String ecosystem, String name, int line) {
    }

    private static final Pattern PEP508_NAME = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)");
    private static final Pattern TOML_DEP_STRING = Pattern.compile("^\"([A-Za-z0-9][A-Za-z0-9._-]*)[^\"]*\"\\s*,?\\s*\\]?\\s*(#.*)?$");
    private static final Pattern JSON_DEP = Pattern.compile("^\"((?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+)\"\\s*:\\s*\"[^\"]*\"\\s*,?$");
    private static final Pattern POM_DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern POM_GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    private DependencyDeclarations() {
    }

    static boolean isManifest(String path) {
        String base = basename(path).toLowerCase(Locale.ROOT);
        return (base.startsWith("requirements") && base.endsWith(".txt"))
                || base.equals("pyproject.toml") || base.equals("package.json") || base.equals("pom.xml");
    }

    static List<Decl> extract(String path, String content, Set<String> addedLines, boolean fileIsAdded) {
        String base = basename(path).toLowerCase(Locale.ROOT);
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if (base.equals("pom.xml")) {
            return extractPom(content, addedLines, fileIsAdded);
        }
        List<Decl> out = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        boolean[] tomlDepLine = base.equals("pyproject.toml") ? tomlDependencyLines(lines) : null;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty() || !(fileIsAdded || addedLines.contains(t))) {
                continue;
            }
            if (base.startsWith("requirements")) {
                String n = requirementName(t);
                if (n != null) {
                    out.add(new Decl("pypi", n, i + 1));
                }
            } else if (tomlDepLine != null) {
                Matcher m = TOML_DEP_STRING.matcher(t);
                if (tomlDepLine[i] && m.find()) {
                    out.add(new Decl("pypi", m.group(1), i + 1));
                }
            } else {
                Matcher m = JSON_DEP.matcher(t);
                if (m.find() && !isJsonMetadataKey(m.group(1))) {
                    out.add(new Decl("npm", m.group(1), i + 1));
                }
            }
        }
        return out;
    }

    private static final Pattern TOML_TABLE = Pattern.compile("^\\[\\s*([^\\]]+?)\\s*\\]\\s*(#.*)?$");
    private static final Pattern TOML_ARRAY_OPEN = Pattern.compile("^([A-Za-z0-9_.\"'-]+)\\s*=\\s*\\[\\s*(#.*)?$");

    /**
     * Marks the lines of a pyproject.toml that sit inside a dependency array. Only {@code dependencies}
     * under {@code [project]}, any array under {@code [project.optional-dependencies]} or
     * {@code [dependency-groups]}, and {@code requires} under {@code [build-system]} qualify. Arrays such
     * as ruff rule codes under {@code [tool.ruff.lint]} are therefore never mistaken for packages
     * (real false positive on apache/airflow#73056: "COM812", "COM819").
     */
    static boolean[] tomlDependencyLines(String[] lines) {
        boolean[] out = new boolean[lines.length];
        String table = "";
        boolean inArray = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (inArray) {
                out[i] = !t.startsWith("]");
                if (t.contains("]")) {
                    inArray = false;
                }
                continue;
            }
            Matcher tm = TOML_TABLE.matcher(t);
            if (tm.find()) {
                table = tm.group(1).trim();
                continue;
            }
            Matcher am = TOML_ARRAY_OPEN.matcher(t);
            if (am.find() && isDependencyArray(table, am.group(1).replace("\"", "").replace("'", ""))) {
                inArray = true;
            }
        }
        return out;
    }

    private static boolean isDependencyArray(String table, String key) {
        if ("project".equals(table)) {
            return "dependencies".equals(key);
        }
        if ("project.optional-dependencies".equals(table) || "dependency-groups".equals(table)) {
            return true;
        }
        return "build-system".equals(table) && "requires".equals(key);
    }

    /** Package name from a requirements line, or null for options, URLs, paths, comments. */
    static String requirementName(String line) {
        if (line.startsWith("#") || line.startsWith("-") || line.contains("://") || line.startsWith(".")
                || line.startsWith("/") || line.contains("\\") || line.contains(" @ ")) {
            return null;
        }
        Matcher m = PEP508_NAME.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isJsonMetadataKey(String key) {
        // Top-level package.json string fields that look like "name": "value" but are not dependencies.
        return Set.of("name", "version", "description", "main", "module", "types", "license", "author",
                "homepage", "repository", "type", "private", "packageManager", "browser", "exports").contains(key);
    }

    private static List<Decl> extractPom(String content, Set<String> addedLines, boolean fileIsAdded) {
        List<Decl> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher dep = POM_DEPENDENCY.matcher(content);
        while (dep.find()) {
            String block = dep.group(1);
            Matcher g = POM_GROUP.matcher(block);
            Matcher a = POM_ARTIFACT.matcher(block);
            boolean complete = g.find() && a.find();
            String group = complete ? g.group(1) : "";
            String artifact = complete ? a.group(1) : "";
            boolean concrete = complete && !group.contains("${") && !artifact.contains("${");
            boolean introduced = concrete
                    && (fileIsAdded || addedLines.contains("<artifactId>" + artifact + "</artifactId>"));
            String name = group + ":" + artifact;
            if (introduced && seen.add(name)) {
                out.add(new Decl("maven", name, lineOf(content, dep.start(1) + a.start())));
            }
        }
        return out;
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String basename(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }
}
