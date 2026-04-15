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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Git-based diff provider. Runs git diff and parses output.
 *
 * @author Vaquar Khan
 */
public final class GitDiffProvider implements DiffProvider {

    private static final Logger log = LoggerFactory.getLogger(GitDiffProvider.class);
    private static final Pattern VALID_REF = Pattern.compile("^[a-zA-Z0-9/_.~^-]+$");
    private static final long DEFAULT_GIT_TIMEOUT_SECONDS = 120;

    /**
     * Max bytes read from a captured {@code git} stdout temp file (default 64MB). Tests may set
     * {@code aiv.git.capture.max.bytes} lower to exercise truncation without huge output.
     */
    private static long maxGitCaptureBytes() {
        String raw = System.getProperty("aiv.git.capture.max.bytes");
        if (raw == null || raw.isBlank()) {
            return 64L * 1024 * 1024;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v < 1 ? 64L * 1024 * 1024 : v;
        } catch (NumberFormatException e) {
            return 64L * 1024 * 1024;
        }
    }

    /**
     * Seconds to wait per {@code git} subprocess. From system property {@code aiv.git.timeout.seconds} when set;
     * otherwise {@link #DEFAULT_GIT_TIMEOUT_SECONDS}. Value {@code 0} means wait until the process exits (use for
     * large-repo benchmarks; risks a hung process if {@code git} stalls).
     */
    private static long gitTimeoutSeconds() {
        String raw = System.getProperty("aiv.git.timeout.seconds");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_GIT_TIMEOUT_SECONDS;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v < 0 ? DEFAULT_GIT_TIMEOUT_SECONDS : v;
        } catch (NumberFormatException e) {
            return DEFAULT_GIT_TIMEOUT_SECONDS;
        }
    }

    /** @return true if the process did not finish (timed out and was destroyed). */
    private static boolean gitProcessTimedOut(Process p, long timeoutSeconds) throws InterruptedException {
        if (timeoutSeconds == 0) {
            p.waitFor();
            return false;
        }
        if (timeoutSeconds < 1) {
            timeoutSeconds = 1;
        }
        if (p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            return false;
        }
        p.destroyForcibly();
        return true;
    }

    /**
     * Runs {@code git} with stdout and stderr appended to a temp file so large diffs cannot deadlock on a full pipe,
     * then reads that file after the process exits.
     *
     * @return captured output, or {@code null} if the process timed out and was destroyed
     */
    private static String runGitReadingFile(ProcessBuilder pb, long timeoutSeconds) throws Exception {
        Path tmp = Files.createTempFile("aiv-git-", ".out");
        try {
            pb.redirectOutput(tmp.toFile());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (gitProcessTimedOut(p, timeoutSeconds)) {
                log.warn("Git subprocess timed out after {}s (command: {})", timeoutSeconds, pb.command());
                for (int i = 0; i < 20; i++) {
                    LockSupport.parkNanos(50_000_000L);
                }
                return null;
            }
            if (!Files.isRegularFile(tmp) || Files.size(tmp) > maxGitCaptureBytes()) {
                return "";
            }
            return Files.readString(tmp, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024; // 2MB

    @Override
    public Diff getDiff(Path workspace, String baseRef, String headRef) {
        validateRef(baseRef);
        validateRef(headRef);
        String rawDiff = runGitDiff(workspace, baseRef, headRef);
        List<ChangedFile> files = parseChangedFiles(workspace, baseRef, headRef);
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

    /**
     * Git executable for child processes. Override with system property {@code aiv.git.executable} (tests only);
     * otherwise {@code git} on {@code PATH}.
     */
    private static String gitCommand() {
        String override = System.getProperty("aiv.git.executable");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return "git";
    }

    /** Child processes are non-TTY; avoid pager or credential prompts hanging indefinitely. */
    private static void configureGitChild(ProcessBuilder pb) {
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_PAGER", "cat");
    }

    private int[] parseNumStat(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "diff", "--numstat", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            String text = runGitReadingFile(pb, timeout);
            if (text == null) {
                return new int[]{0, 0};
            }
            int added = 0, deleted = 0;
            for (String line : text.split("\n")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    added += parseNum(parts[0]);
                    deleted += parseNum(parts[1]);
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
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "log", "-1", "--format=%ae", baseRef + ".." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            String text = runGitReadingFile(pb, timeout);
            if (text == null) {
                return null;
            }
            String line = text.lines().findFirst().orElse("");
            return (!line.isBlank()) ? line.trim() : null;
        } catch (Exception e) {
            log.debug("Could not parse author", e);
            return null;
        }
    }

    private boolean parseSkipRequested(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "log", "--pretty=%B", baseRef + ".." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            String text = runGitReadingFile(pb, timeout);
            if (text == null) {
                return false;
            }
            for (String line : text.split("\n")) {
                if (line.contains("/aiv skip") || line.contains("aiv skip")) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse skip request", e);
        }
        return false;
    }

    private String runGitDiff(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "diff", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            String text = runGitReadingFile(pb, timeout);
            if (text == null) {
                return "";
            }
            return text;
        } catch (Exception e) {
            log.debug("Could not run git diff: {}", e.getMessage());
            return "";
        }
    }

    private List<ChangedFile> parseChangedFiles(Path workspace, String baseRef, String headRef) {
        List<ChangedFile> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "diff", "--name-status", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            String nameStatus = runGitReadingFile(pb, timeout);
            if (nameStatus == null) {
                return result;
            }
            for (String line : nameStatus.split("\n")) {
                // `git diff --name-status` is tab-separated and may include multiple paths (e.g. rename/copy).
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;

                String status = parts[0].trim();
                if (status.isEmpty()) continue;

                String pathPart;
                if (status.startsWith("R") || status.startsWith("C")) {
                    if (parts.length < 3) continue;
                    pathPart = parts[2];
                } else {
                    pathPart = parts[1];
                }

                // Deleted files have no content to validate in the head ref; skip them.
                if (status.startsWith("D")) {
                    continue;
                }

                String path = sanitizePath(pathPart);
                if (path == null) continue;

                ChangedFile.ChangeType type = status.startsWith("A")
                        ? ChangedFile.ChangeType.ADDED
                        : ChangedFile.ChangeType.MODIFIED;

                String content = readFileContent(workspace, path, headRef);
                result.add(new ChangedFile(path, type, content));
            }
        } catch (Exception e) {
            log.debug("Could not parse changed files", e);
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
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "show", ref + ":" + gitPath);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            String content = runGitReadingFile(pb, timeout);
            if (content == null) {
                return "";
            }
            return content.length() > MAX_FILE_SIZE_BYTES ? "" : content;
        } catch (Exception e) {
            log.debug("Could not read file content: {}", relativePath, e);
            return "";
        }
    }
}
