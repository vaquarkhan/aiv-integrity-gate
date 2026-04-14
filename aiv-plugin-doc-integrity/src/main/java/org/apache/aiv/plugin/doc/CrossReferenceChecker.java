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

package org.apache.aiv.plugin.doc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects cross-reference phrases like "read the README in X" and verifies the referenced file exists.
 *
 * @author Vaquar Khan
 */
public final class CrossReferenceChecker {

    private static final Pattern[] CROSS_REF_PATTERNS = {
            Pattern.compile("(?i)read\\s+the\\s+README\\s+(?:in|at)\\s+([^\\s.,;:]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)see\\s+the\\s+docs?\\s+(?:in|at)\\s+([^\\s.,;:]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)refer\\s+to\\s+([^\\s.,;:]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)README\\s+(?:in|at)\\s+([^\\s.,;:]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)documentation\\s+(?:in|at)\\s+([^\\s.,;:]+)", Pattern.CASE_INSENSITIVE)
    };

    private final Path workspace;

    public CrossReferenceChecker(Path workspace) {
        this.workspace = workspace;
    }

    public String validate(String docPath, String content) {
        if (content == null || content.isEmpty()) return null;
        for (Pattern p : CROSS_REF_PATTERNS) {
            Matcher m = p.matcher(content);
            while (m.find()) {
                String ref = m.group(1).trim();
                if (!ref.isEmpty() && !resolveExists(ref, docPath)) {
                    return String.format("Cross-reference '%s' in %s: referenced location does not exist", ref, docPath);
                }
            }
        }
        return null;
    }

    boolean resolveExists(String pathStr, String docPath) {
        String normalized = pathStr.replace("\\", "/").trim();
        if (normalized.startsWith("~/")) normalized = normalized.substring(2);
        else if (normalized.startsWith("/")) normalized = normalized.substring(1);
        Path baseDir = workspace;
        if (docPath != null && docPath.contains("/")) {
            Path parent = workspace.resolve(docPath).getParent();
            if (parent != null) baseDir = parent;
        }
        Path workspaceAbs = workspace.toAbsolutePath().normalize();
        Path primary = baseDir.resolve(normalized).normalize();
        Path fromRoot = DocPathUtils.resolveFromWorkspaceRoot(workspace, normalized);
        Path resolved = primary.startsWith(workspaceAbs) ? primary : fromRoot;
        if (Files.exists(resolved)) return true;
        Path withReadme = resolved.resolve("README.md");
        return Files.exists(withReadme);
    }
}
