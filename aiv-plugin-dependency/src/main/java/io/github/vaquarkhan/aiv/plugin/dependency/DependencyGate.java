/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aiv.plugin.dependency;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that imports are covered by declared dependencies and known package roots.
 *
 * @author Vaquar Khan
 */
public final class DependencyGate implements QualityGate {

    private static final Logger log = LoggerFactory.getLogger(DependencyGate.class);
    private static final Set<String> JAVA_BUILTIN_PREFIXES = Set.of("java.", "javax.", "jakarta.");
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*(?:import|from)\\s+([a-zA-Z0-9_.]+)");
    private static final Pattern GROUP_ID_TAG = Pattern.compile("<groupId>\\s*([^<]+?)\\s*</groupId>");

    @Override
    public String getId() {
        return "dependency";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Path workspace = context.getWorkspace();
        Set<String> javaAllowedPrefixes = loadJavaDependencyPrefixes(workspace);
        Set<String> pyAllowed = loadPythonDependencies(workspace);
        Set<String> whitelist = getWhitelist(context);

        List<String> violations = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            String path = file.getPath();
            String content = file.getContent();
            if (path.endsWith(".java")) {
                checkJavaImports(content, path, javaAllowedPrefixes, whitelist, violations, findings);
            } else if (path.endsWith(".py")) {
                checkPythonImports(content, path, pyAllowed, whitelist, violations, findings);
            }
        }
        if (violations.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("\n", violations), findings);
    }

    private void checkJavaImports(String content, String path, Set<String> allowedPrefixes,
                                  Set<String> whitelist, List<String> violations, List<Finding> findings) {
        ParseResult<CompilationUnit> result = new JavaParser().parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return;
        }
        for (ImportDeclaration imp : result.getResult().get().getImports()) {
            if (imp.isStatic()) continue;
            String name = imp.getNameAsString();
            if (isJavaBuiltin(name)) continue;
            String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            if (!isAllowed(pkg, allowedPrefixes, whitelist)) {
                String msg = String.format(
                        "Import '%s' in %s is not covered by declared dependencies (configure dependency gate whitelist if intentional)",
                        name, path);
                violations.add(msg);
                int line = imp.getRange().map(r -> r.begin.line).orElse(1);
                findings.add(Finding.atLine("dependency.unresolved-import", path, line, msg));
            }
        }
    }

    private void checkPythonImports(String content, String path, Set<String> allowed, Set<String> whitelist,
                                    List<String> violations, List<Finding> findings) {
        int lineNum = 0;
        for (String line : content.split("\n")) {
            lineNum++;
            Matcher m = PY_IMPORT.matcher(line);
            if (!m.find()) continue;
            String mod = m.group(1).split("\\.")[0];
            if (mod.equals("__future__") || mod.startsWith("_")) continue;
            String modNorm = mod.replace("_", "-");
            if (!isAllowedPy(mod, modNorm, allowed, whitelist)) {
                String msg = String.format(
                        "Import '%s' in %s not in requirements.txt (configure dependency gate whitelist if intentional)",
                        mod, path);
                violations.add(msg);
                findings.add(Finding.atLine("dependency.unresolved-python-import", path, lineNum, msg));
                return;
            }
        }
    }

    private boolean isAllowedPy(String mod, String modNorm, Set<String> allowed, Set<String> whitelist) {
        if (whitelist.contains(mod) || whitelist.contains(modNorm)) return true;
        if (allowed.isEmpty()) return true;
        for (String pkg : allowed) {
            String pkgNorm = pkg.replace("_", "-");
            if (mod.equals(pkg) || modNorm.equals(pkgNorm)) return true;
            if (mod.equals(pkgNorm) || modNorm.equals(pkg)) return true;
        }
        return false;
    }

    private boolean isJavaBuiltin(String name) {
        return JAVA_BUILTIN_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private boolean isAllowed(String pkg, Set<String> allowedPrefixes, Set<String> whitelist) {
        if (whitelist.contains(pkg)) return true;
        if (allowedPrefixes.isEmpty()) return true;
        for (String prefix : allowedPrefixes) {
            if (pkg.equals(prefix) || pkg.startsWith(prefix + ".")) return true;
            if (prefix.startsWith(pkg + ".") || prefix.equals(pkg)) return true;
        }
        return false;
    }

    private Set<String> loadJavaDependencyPrefixes(Path workspace) {
        Set<String> allowed = new HashSet<>();
        Path pom = workspace.resolve("pom.xml");
        if (!Files.exists(pom)) return allowed;
        try {
            String xml = Files.readString(pom);
            String projectGroup = extractGroupId(xml);
            if (projectGroup != null) {
                allowed.addAll(KnownJavaPackageRoots.expandPrefixes(projectGroup, ""));
            }
            for (String[] ga : extractDependencies(xml)) {
                String g = ga[0];
                String a = ga[1];
                if (g != null && !g.isEmpty()) {
                    allowed.addAll(KnownJavaPackageRoots.expandPrefixes(g, a != null ? a : ""));
                }
            }
        } catch (Exception e) {
            log.debug("Could not load Maven dependencies from pom.xml", e);
        }
        return allowed;
    }

    static String extractGroupId(String xml) {
        int parentStart = xml.indexOf("<parent>");
        String slice = xml;
        if (parentStart >= 0) {
            int parentEnd = xml.indexOf("</parent>", parentStart);
            if (parentEnd > parentStart) {
                slice = xml.substring(parentEnd + "</parent>".length());
            }
        }
        Matcher m = GROUP_ID_TAG.matcher(slice);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private List<String[]> extractDependencies(String xml) {
        List<String[]> result = new ArrayList<>();
        int pos = 0;
        while ((pos = xml.indexOf("<dependency>", pos)) >= 0) {
            int end = xml.indexOf("</dependency>", pos);
            if (end < 0) break;
            String block = xml.substring(pos, end);
            String g = extractTag(block, "groupId");
            String a = extractTag(block, "artifactId");
            if (g != null && a != null) {
                result.add(new String[]{g, a});
            }
            pos = end + 1;
        }
        return result;
    }

    private String extractTag(String block, String tag) {
        int i = block.indexOf("<" + tag + ">");
        if (i < 0) return null;
        int j = block.indexOf("</" + tag + ">", i);
        if (j < 0) return null;
        return block.substring(i + tag.length() + 2, j).trim();
    }

    private Set<String> loadPythonDependencies(Path workspace) {
        Set<String> allowed = new HashSet<>();
        for (String name : List.of("requirements.txt", "pyproject.toml")) {
            Path p = workspace.resolve(name);
            if (!Files.exists(p)) continue;
            try {
                String content = Files.readString(p);
                if (name.endsWith(".txt")) {
                    for (String line : content.split("\n")) {
                        String pkg = line.split("[=<>!]")[0].trim().replace("_", "-");
                        if (!pkg.isEmpty() && !pkg.startsWith("#")) allowed.add(pkg);
                    }
                } else {
                    Matcher m = Pattern.compile("dependencies\\s*=\\s*\\[([\\s\\S]*?)\\]").matcher(content);
                    if (m.find()) {
                        for (String dep : m.group(1).split(",")) {
                            String pkg = dep.replaceAll("[\"']", "").trim().split("[=<>!]")[0].trim();
                            if (!pkg.isEmpty()) allowed.add(pkg);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not load Python dependencies from {}", name, e);
            }
        }
        return allowed;
    }

    @SuppressWarnings("unchecked")
    private Set<String> getWhitelist(AIVContext context) {
        Object v = context.getConfig().getGates().stream()
                .filter(g -> getId().equals(g.getId()))
                .findFirst()
                .map(AIVConfig.GateConfig::getConfig)
                .orElse(Map.of())
                .get("whitelist");
        if (v instanceof List<?> list) {
            Set<String> s = new HashSet<>();
            list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(s::add);
            return s;
        }
        return Set.of();
    }
}
