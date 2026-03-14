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

package org.apache.aiv.plugin.dependency;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import org.apache.aiv.model.AIVConfig;
import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.QualityGate;

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
 * Validates that new imports match project dependencies. Prevents Dependency Confusion.
 *
 * @author Vaquar Khan
 */
public final class DependencyGate implements QualityGate {

    private static final Set<String> JAVA_BUILTIN_PREFIXES = Set.of("java.", "javax.", "jakarta.");
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*(?:import|from)\\s+([a-zA-Z0-9_.]+)");
    private static final Pattern PY_FROM = Pattern.compile("^\\s*from\\s+([a-zA-Z0-9_.]+)\\s+import");

    @Override
    public String getId() {
        return "dependency";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Path workspace = context.getWorkspace();
        Set<String> javaAllowed = loadJavaDependencies(workspace);
        Set<String> pyAllowed = loadPythonDependencies(workspace);
        Set<String> whitelist = getWhitelist(context);

        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            String path = file.getPath();
            String content = file.getContent();
            if (path.endsWith(".java")) {
                GateResult r = checkJavaImports(content, path, javaAllowed, whitelist);
                if (r != null) return r;
            } else if (path.endsWith(".py")) {
                GateResult r = checkPythonImports(content, path, pyAllowed, whitelist);
                if (r != null) return r;
            }
        }
        return GateResult.pass(getId());
    }

    private GateResult checkJavaImports(String content, String path, Set<String> allowed, Set<String> whitelist) {
        ParseResult<CompilationUnit> result = new JavaParser().parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) return null;
        for (ImportDeclaration imp : result.getResult().get().getImports()) {
            if (imp.isStatic()) continue;
            String name = imp.getNameAsString();
            if (isJavaBuiltin(name)) continue;
            String pkg = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            if (!isAllowed(pkg, allowed, whitelist)) {
                return GateResult.fail(getId(),
                        String.format("Import '%s' in %s not in pom.xml - possible Dependency Confusion", name, path));
            }
        }
        return null;
    }

    private GateResult checkPythonImports(String content, String path, Set<String> allowed, Set<String> whitelist) {
        for (String line : content.split("\n")) {
            Matcher m = PY_IMPORT.matcher(line);
            if (!m.find()) continue;
            String mod = m.group(1).split("\\.")[0];
            if (mod.equals("__future__") || mod.startsWith("_")) continue;
            String modNorm = mod.replace("_", "-");
            if (!isAllowedPy(mod, modNorm, allowed, whitelist)) {
                return GateResult.fail(getId(),
                        String.format("Import '%s' in %s not in requirements.txt - possible Dependency Confusion", mod, path));
            }
        }
        return null;
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

    private boolean isAllowed(String pkg, Set<String> deps, Set<String> whitelist) {
        if (whitelist.contains(pkg)) return true;
        if (deps.isEmpty()) return true;
        for (String dep : deps) {
            if (pkg.equals(dep) || pkg.startsWith(dep + ".")) return true;
            if (dep.startsWith(pkg + ".") || dep.equals(pkg)) return true;
        }
        return false;
    }

    private Set<String> loadJavaDependencies(Path workspace) {
        Set<String> allowed = new HashSet<>();
        Path pom = workspace.resolve("pom.xml");
        if (!Files.exists(pom)) return allowed;
        try {
            String xml = Files.readString(pom);
            String groupId = extractGroupId(xml);
            if (groupId != null) allowed.add(groupId);
            for (String gav : extractDependencies(xml)) {
                String[] parts = gav.split(":");
                if (parts.length == 0) continue;
                String g = parts[0];
                if (g != null && !g.isEmpty()) allowed.add(g);
            }
        } catch (Exception e) {
            // ignore
        }
        return allowed;
    }

    private String extractGroupId(String xml) {
        int i = xml.indexOf("<groupId>");
        if (i < 0) return null;
        int j = xml.indexOf("</groupId>", i);
        if (j < 0) return null;
        return xml.substring(i + 9, j).trim();
    }

    private List<String> extractDependencies(String xml) {
        List<String> result = new ArrayList<>();
        int pos = 0;
        while ((pos = xml.indexOf("<dependency>", pos)) >= 0) {
            int end = xml.indexOf("</dependency>", pos);
            if (end < 0) break;
            String block = xml.substring(pos, end);
            String g = extractTag(block, "groupId");
            String a = extractTag(block, "artifactId");
            if (g != null && a != null) result.add(g + ":" + a);
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
                // ignore
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
