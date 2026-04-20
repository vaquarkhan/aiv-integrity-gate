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

package io.github.vaquarkhan.aiv.plugin.doc;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts paths from docs and flags those with zero matches in the codebase as potential fabrications.
 *
 * @author Vaquar Khan
 */
public final class PathFabricationDetector {

    private static final Logger log = LoggerFactory.getLogger(PathFabricationDetector.class);
    private static final Pattern STRUCTURED_PATH_PATTERN = Pattern.compile(
            "(?:^|[\\s\"'(])((?:~/[\\w./-]+|/[\\w./-]+|[\\w][\\w./-]*(?:/[\\w./-]+)+))(?=[\\s\"')]|$)");
    private static final Pattern FILE_TOKEN_PATTERN = Pattern.compile(
            "(?:^|[\\s\"'(])([\\w.-]+\\.(?:sh|bash|zsh|md|rst|txt|java|kt|kts|py|go|rs|js|ts|tsx|jsx|json|yaml|yml|xml|toml))(?=[\\s\"').,;:]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_FILES_TO_SCAN = 500;
    private static final int MAX_CHARS_PER_FILE = 50000;

    private final Set<String> workspaceMentions;
    private final AIVContext context;

    /** Walks the workspace once at construction time. */
    public PathFabricationDetector(AIVContext context) {
        this.context = context;
        this.workspaceMentions = walkWorkspace(context);
    }

    private static Set<String> walkWorkspace(AIVContext context) {
        Set<String> mentions = new HashSet<>();
        Set<Path> seen = new HashSet<>();
        try {
            int count = 0;
            try (var stream = Files.walk(context.getWorkspace(), 8)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (count++ > MAX_FILES_TO_SCAN) break;
                    if (!Files.isRegularFile(p)) continue;
                    String name = p.getFileName().toString().toLowerCase();
                    if (!name.endsWith(".md") && !name.endsWith(".txt") && !name.endsWith(".rst")
                            && !name.endsWith(".java") && !name.endsWith(".py") && !name.endsWith(".sh")
                            && !name.endsWith(".yaml") && !name.endsWith(".yml")) continue;
                    if (seen.contains(p)) continue;
                    seen.add(p);
                    try {
                        String content = Files.readString(p);
                        if (content.length() > MAX_CHARS_PER_FILE) content = content.substring(0, MAX_CHARS_PER_FILE);
                        mentions.addAll(extractPathLikeTokens(content));
                    } catch (Exception e) {
                        log.debug("Skipping unreadable file {}", p, e);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Workspace walk failed", e);
        }
        return mentions;
    }

    public String validate(String docPath, String content) {
        List<String> all = validateAll(docPath, content);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<String> validateAll(String docPath, String content) {
        if (content == null || content.isEmpty()) return List.of();
        List<String> paths = extractPaths(content);
        List<String> violations = new ArrayList<>();
        for (String p : paths) {
            if (!isLikelyPath(p) || isExcluded(p)) continue;
            if (!appearsOutsideCurrentDoc(p, docPath)) {
                violations.add(String.format("Path '%s' in %s has no matches in codebase (possible fabrication)", p, docPath));
            }
        }
        return violations;
    }

    private boolean appearsOutsideCurrentDoc(String candidate, String docPath) {
        if (workspaceMentions.contains(candidate)) {
            return true;
        }
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            if (docPath.equals(f.getPath())) {
                continue;
            }
            if (f.getPath().contains(candidate) || f.getContent().contains(candidate)
                    || extractPathLikeTokens(f.getContent()).contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> extractPathLikeTokens(String content) {
        Set<String> result = new HashSet<>();
        if (content == null || content.isEmpty()) {
            return result;
        }
        Matcher structured = STRUCTURED_PATH_PATTERN.matcher(content);
        while (structured.find()) {
            String path = structured.group(1).trim();
            if (!path.isEmpty()) result.add(path);
        }
        Matcher fileTokens = FILE_TOKEN_PATTERN.matcher(content);
        while (fileTokens.find()) {
            String token = fileTokens.group(1).trim();
            if (!token.isEmpty()) result.add(token);
        }
        return result;
    }

    private List<String> extractPaths(String content) {
        Set<String> result = new LinkedHashSet<>();
        Matcher structured = STRUCTURED_PATH_PATTERN.matcher(content);
        while (structured.find()) {
            String path = structured.group(1).trim();
            if (!path.isEmpty()) result.add(path);
        }
        Matcher fileTokens = FILE_TOKEN_PATTERN.matcher(content);
        while (fileTokens.find()) {
            String token = fileTokens.group(1).trim();
            if (!token.isEmpty()) result.add(token);
        }
        return new ArrayList<>(result);
    }

    private boolean isLikelyPath(String s) {
        if (s.length() < 5) return false;
        if (s.contains(" ")) return false;
        if (s.contains("/") || s.startsWith("~/") || s.startsWith("/")) return true;
        int dot = s.lastIndexOf('.');
        return dot > 0 && dot + 1 < s.length();
    }

    private boolean isExcluded(String path) {
        String lower = path.toLowerCase();
        return lower.startsWith("http") || lower.contains("://") || lower.endsWith(".com");
    }
}
