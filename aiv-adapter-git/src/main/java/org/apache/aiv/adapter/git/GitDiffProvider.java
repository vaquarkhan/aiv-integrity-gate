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

package org.apache.aiv.adapter.git;

import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.Diff;
import org.apache.aiv.port.DiffProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Git-based diff provider. Runs git diff and parses output.
 *
 * @author Vaquar Khan
 */
public final class GitDiffProvider implements DiffProvider {

    private static final Logger log = LoggerFactory.getLogger(GitDiffProvider.class);
    private static final Pattern VALID_REF = Pattern.compile("^[a-zA-Z0-9/_.~^-]+$");
    private static final long GIT_TIMEOUT_SECONDS = 60;
    private static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024; // 2MB

    @Override
    public Diff getDiff(Path workspace, String baseRef, String headRef) {
        validateRef(baseRef);
        validateRef(headRef);
        String rawDiff = runGitDiff(workspace, baseRef, headRef);
        List<ChangedFile> files = parseChangedFiles(workspace, baseRef, headRef, rawDiff);
        int[] loc = parseNumStat(workspace, baseRef, headRef);
        String author = parseAuthor(workspace, baseRef, headRef);
        boolean skip = parseSkipRequested(workspace, baseRef, headRef);
        return new Diff(baseRef, headRef, files, rawDiff, loc[0], loc[1], author, skip);
    }

    private static void validateRef(String ref) {
        if (ref == null || ref.isBlank() || !VALID_REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("Invalid git ref: " + ref);
        }
    }

    private int[] parseNumStat(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--numstat", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Git diff --numstat timed out after {}s", GIT_TIMEOUT_SECONDS);
                return new int[]{0, 0};
            }
            int added = 0, deleted = 0;
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        added += parseNum(parts[0]);
                        deleted += parseNum(parts[1]);
                    }
                }
            }
            return new int[]{added, deleted};
        } catch (Exception e) {
            log.debug("Could not parse numstat: {}", e.getMessage());
            return new int[]{0, 0};
        }
    }

    private int parseNum(String s) {
        if (s == null || s.equals("-")) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String parseAuthor(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "log", "-1", "--format=%ae", baseRef + ".." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return (line != null && !line.isBlank()) ? line.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private boolean parseSkipRequested(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "log", "--pretty=%B", baseRef + ".." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("/aiv skip") || line.contains("aiv skip")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private String runGitDiff(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Git diff timed out after {}s", GIT_TIMEOUT_SECONDS);
                return "";
            }
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.debug("Could not run git diff: {}", e.getMessage());
            return "";
        }
    }

    private List<ChangedFile> parseChangedFiles(Path workspace, String baseRef, String headRef, String rawDiff) {
        List<ChangedFile> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-status", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return result;
            }
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length < 2) continue;
                    String status = parts[0];
                    String path = sanitizePath(parts[1]);
                    if (path == null) continue;
                    ChangedFile.ChangeType type = "A".equals(status) ? ChangedFile.ChangeType.ADDED : ChangedFile.ChangeType.MODIFIED;
                    String content = readFileContent(workspace, path, headRef);
                    result.add(new ChangedFile(path, type, content));
                }
            }
        } catch (Exception e) {
            // fallback: no files
        }
        return result;
    }

    private static String sanitizePath(String path) {
        if (path == null || path.isBlank()) return null;
        String normalized = path.replace("\\", "/").replaceAll("/+", "/");
        if (normalized.contains("..") || normalized.startsWith("/")) return null;
        return normalized;
    }

    private String readFileContent(Path workspace, String relativePath, String ref) {
        if (relativePath == null || !relativePath.equals(sanitizePath(relativePath))) return "";
        Path workspaceAbs = workspace.toAbsolutePath().normalize();
        Path fullPath = workspace.resolve(relativePath).normalize().toAbsolutePath();
        if (!fullPath.startsWith(workspaceAbs)) return "";
        try {
            if (Files.exists(fullPath)) {
                if (Files.size(fullPath) > MAX_FILE_SIZE_BYTES) return "";
                return Files.readString(fullPath);
            }
            String gitPath = relativePath.replace("\\", "/");
            ProcessBuilder pb = new ProcessBuilder("git", "show", ref + ":" + gitPath);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String content = reader.lines().collect(Collectors.joining("\n"));
                return content.length() > MAX_FILE_SIZE_BYTES ? "" : content;
            }
        } catch (Exception e) {
            return "";
        }
    }
}
