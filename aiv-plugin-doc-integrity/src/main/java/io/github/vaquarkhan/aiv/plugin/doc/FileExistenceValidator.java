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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans doc content for path patterns and verifies they exist in the repo.
 *
 * @author Vaquar Khan
 */
public final class FileExistenceValidator {

    private static final Pattern PATH_PATTERN = Pattern.compile(
            "(?:^|[\\s\"'(])((?:" +
            "~/[\\w./-]+" +
            "|/[\\w./-]+" +
            "|[\\w][\\w./-]*(?:/[\\w./-]+)+" +
            "))(?=[\\s\"')]|$)");

    private final Path workspace;

    public FileExistenceValidator(Path workspace) {
        this.workspace = workspace;
    }

    public String validate(String docPath, String content) {
        if (content == null || content.isEmpty()) return null;
        List<String> paths = extractPaths(content);
        for (String p : paths) {
            if (isLikelyPath(p) && !resolveExists(p, docPath)) {
                return String.format("Path '%s' in %s does not exist in repo", p, docPath);
            }
        }
        return null;
    }

    private List<String> extractPaths(String content) {
        List<String> result = new ArrayList<>();
        Matcher m = PATH_PATTERN.matcher(content);
        while (m.find()) {
            String path = m.group(1).trim();
            if (!path.isEmpty() && !isExcluded(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private boolean isExcluded(String path) {
        String lower = path.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.contains("://") || lower.endsWith(".com")
                || path.matches(".*\\d{4}-\\d{2}-\\d{2}.*");
    }

    private boolean isLikelyPath(String s) {
        if (s.length() < 3) return false;
        if (s.contains(" ") || s.contains("\n")) return false;
        return s.contains("/") || s.startsWith("~/") || s.startsWith("/");
    }

    boolean resolveExists(String pathStr, String docPath) {
        String normalized = pathStr.replace("\\", "/").trim();
        if (normalized.startsWith("~/")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path baseDir = workspace;
        if (docPath.contains("/")) {
            baseDir = workspace.resolve(docPath).getParent();
            if (baseDir == null) baseDir = workspace;
        }
        Path workspaceAbs = workspace.toAbsolutePath().normalize();
        Path primary = baseDir.resolve(normalized).normalize();
        Path fromRoot = DocPathUtils.resolveFromWorkspaceRoot(workspace, normalized);
        Path resolved = primary.startsWith(workspaceAbs) ? primary : fromRoot;
        return Files.exists(resolved);
    }
}
