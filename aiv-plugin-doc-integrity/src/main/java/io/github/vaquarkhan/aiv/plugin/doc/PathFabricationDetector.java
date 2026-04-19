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
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "(?:^|[\\s\"'(])((?:~/[\\w./-]+|/[\\w./-]+|[\\w][\\w./-]*(?:/[\\w./-]+)+))(?=[\\s\"')]|$)");
    private static final int MAX_FILES_TO_SCAN = 500;
    private static final int MAX_CHARS_PER_FILE = 50000;

    private final String workspaceBlob;
    private final AIVContext context;

    /**
     * Walks the workspace once; per {@link #validate(String, String)} call, other changed files (not the doc under
     * validation) are merged so a path cannot "match" only inside the same document.
     */
    public PathFabricationDetector(AIVContext context) {
        this.context = context;
        this.workspaceBlob = walkWorkspace(context);
    }

    private static String walkWorkspace(AIVContext context) {
        StringBuilder sb = new StringBuilder();
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
                        sb.append(" ").append(content);
                    } catch (Exception e) {
                        log.debug("Skipping unreadable file {}", p, e);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Workspace walk failed", e);
        }
        return sb.toString();
    }

    private String searchBlobExcludingDoc(String docPath) {
        StringBuilder sb = new StringBuilder(workspaceBlob);
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            if (docPath.equals(f.getPath())) {
                continue;
            }
            sb.append(" ").append(f.getPath()).append(" ").append(f.getContent());
        }
        return sb.toString();
    }

    public String validate(String docPath, String content) {
        if (content == null || content.isEmpty()) return null;
        String blob = searchBlobExcludingDoc(docPath);
        List<String> paths = extractPaths(content);
        for (String p : paths) {
            if (!isLikelyPath(p) || isExcluded(p)) continue;
            if (!blob.contains(p)) {
                return String.format("Path '%s' in %s has no matches in codebase (possible fabrication)", p, docPath);
            }
        }
        return null;
    }

    private List<String> extractPaths(String content) {
        List<String> result = new ArrayList<>();
        Matcher m = PATH_PATTERN.matcher(content);
        while (m.find()) {
            String path = m.group(1).trim();
            if (!path.isEmpty()) result.add(path);
        }
        return result;
    }

    private boolean isLikelyPath(String s) {
        if (s.length() < 5) return false;
        return (s.contains("/") || s.startsWith("~/") || s.startsWith("/")) && !s.contains(" ");
    }

    private boolean isExcluded(String path) {
        String lower = path.toLowerCase();
        return lower.startsWith("http") || lower.contains("://") || lower.endsWith(".com");
    }
}
