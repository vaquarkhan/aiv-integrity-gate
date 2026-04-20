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

package io.github.vaquarkhan.aiv.adapter.git;

import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final Pattern SKIP_DIRECTIVE_LINE = Pattern.compile("^\\s*/?aiv\\s+skip\\s*$");

    /**
     * Max bytes read from a captured {@code git} stdout temp file (default 64MB). Tests may set
     * {@code aiv.git.capture.max.bytes} lower to exercise truncation without huge output.
     */
    static long maxGitCaptureBytes() {
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

    static long gitTimeoutSeconds() {
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

    static boolean gitProcessTimedOut(Process p, long timeoutSeconds) throws InterruptedException {
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

    private record GitCapture(int exitCode, String stdout, boolean timedOut) {}

    /**
     * Runs {@code git} with stdout appended to a temp file, then reads that file after the process exits.
     *
     * @return capture with {@code timedOut true} if the process was destroyed; {@code stdout} may be null when timed out
     */
    private static GitCapture runGitCapture(ProcessBuilder pb, long timeoutSeconds) throws Exception {
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
                return new GitCapture(-1, null, true);
            }
            int exit = p.exitValue();
            if (!Files.isRegularFile(tmp) || Files.size(tmp) > maxGitCaptureBytes()) {
                return new GitCapture(exit, "", false);
            }
            return new GitCapture(exit, Files.readString(tmp, StandardCharsets.UTF_8), false);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024; // 2MB

    @Override
    public Diff getDiff(Path workspace, String baseRef, String headRef) {
        validateRef(baseRef);
        validateRef(headRef);
        List<String> warnings = new ArrayList<>();
        String rawDiff = runGitDiffStrict(workspace, baseRef, headRef);
        NumStatResult num = parseNumStatStrict(workspace, baseRef, headRef);
        List<ChangedFile> files = parseChangedFiles(workspace, baseRef, headRef, warnings);
        String author = parseAuthor(workspace, headRef);
        boolean signed = parseHeadCommitSigned(workspace, headRef);
        boolean skip = parseSkipDirectiveInLatestCommit(workspace, headRef);
        return new Diff(baseRef, headRef, files, rawDiff, num.added(), num.deleted(), author, signed, skip,
                warnings, num.perFileNet());
    }

    private static void validateRef(String ref) {
        if (ref == null || ref.isBlank() || !VALID_REF.matcher(ref).matches()) {
            throw new IllegalArgumentException("Invalid git ref: " + ref);
        }
    }

    private static String gitCommand() {
        String override = System.getProperty("aiv.git.executable");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return "git";
    }

    private static void configureGitChild(ProcessBuilder pb) {
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_PAGER", "cat");
    }

    record NumStatResult(int added, int deleted, Map<String, Integer> perFileNet) {}

    NumStatResult parseNumStatStrict(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "diff", "--numstat", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            GitCapture cap = runGitCapture(pb, timeout);
            if (cap.timedOut()) {
                throw new IllegalStateException("git diff --numstat timed out");
            }
            if (cap.exitCode() != 0) {
                throw new IllegalStateException("git diff --numstat failed with exit " + cap.exitCode());
            }
            String text = cap.stdout() != null ? cap.stdout() : "";
            int added = 0;
            int deleted = 0;
            Map<String, Integer> perFile = new HashMap<>();
            for (String line : text.split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t");
                if (parts.length < 3) continue;
                added += parseNum(parts[0]);
                deleted += parseNum(parts[1]);
                String pathKey = parts[2].trim().replace("\\", "/");
                perFile.put(pathKey, parseNum(parts[0]) - parseNum(parts[1]));
            }
            return new NumStatResult(added, deleted, Map.copyOf(perFile));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("git diff --numstat failed: " + e.getMessage(), e);
        }
    }

    int parseNum(String s) {
        if (s == null || s.equals("-")) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    String parseAuthor(Path workspace, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "log", "-1", "--format=%ae", headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            GitCapture cap = runGitCapture(pb, timeout);
            if (cap.timedOut() || cap.exitCode() != 0 || cap.stdout() == null) {
                return null;
            }
            String line = cap.stdout().lines().findFirst().orElse("");
            return (!line.isBlank()) ? line.trim() : null;
        } catch (Exception e) {
            log.debug("Could not parse author", e);
            return null;
        }
    }

    boolean parseHeadCommitSigned(Path workspace, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "log", "-1", "--format=%G?", headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            GitCapture cap = runGitCapture(pb, timeout);
            if (cap.timedOut() || cap.exitCode() != 0 || cap.stdout() == null) {
                return false;
            }
            String status = cap.stdout().lines().findFirst().orElse("").trim();
            // G/U/X/Y/R are signed states; N means unsigned.
            return "G".equals(status) || "U".equals(status) || "X".equals(status)
                    || "Y".equals(status) || "R".equals(status);
        } catch (Exception e) {
            log.debug("Could not parse signature status", e);
            return false;
        }
    }

    /**
     * Only the latest commit on {@code headRef} is considered. A line triggers skip when it matches the anchored
     * pattern (optional leading slash, literal {@code aiv}, whitespace, {@code skip}, end of line).
     */
    boolean parseSkipDirectiveInLatestCommit(Path workspace, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "log", "-1", "--pretty=%B", headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            GitCapture cap = runGitCapture(pb, timeout);
            if (cap.timedOut() || cap.exitCode() != 0 || cap.stdout() == null) {
                return false;
            }
            for (String line : cap.stdout().split("\n")) {
                if (SKIP_DIRECTIVE_LINE.matcher(line).matches()) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse skip directive", e);
        }
        return false;
    }

    String runGitDiffStrict(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "diff", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            GitCapture cap = runGitCapture(pb, timeout);
            if (cap.timedOut()) {
                throw new IllegalStateException("git diff timed out");
            }
            if (cap.exitCode() != 0) {
                throw new IllegalStateException("git diff failed with exit " + cap.exitCode());
            }
            return cap.stdout() != null ? cap.stdout() : "";
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("git diff failed: " + e.getMessage(), e);
        }
    }

    List<ChangedFile> parseChangedFiles(Path workspace, String baseRef, String headRef, List<String> warnings) {
        List<ChangedFile> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "diff", "--name-status", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            GitCapture cap = runGitCapture(pb, timeout);
            if (cap.timedOut()) {
                throw new IllegalStateException("git diff --name-status timed out");
            }
            if (cap.exitCode() != 0) {
                throw new IllegalStateException("git diff --name-status failed with exit " + cap.exitCode());
            }
            String nameStatus = cap.stdout() != null ? cap.stdout() : "";
            for (String line : nameStatus.split("\n")) {
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

                if (status.startsWith("D")) {
                    continue;
                }

                String path = sanitizePath(pathPart);
                if (path == null) continue;

                ChangedFile.ChangeType type = status.startsWith("A")
                        ? ChangedFile.ChangeType.ADDED
                        : ChangedFile.ChangeType.MODIFIED;

                String content = readFileContent(workspace, path, headRef, warnings);
                result.add(new ChangedFile(path, type, content));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("git diff --name-status failed: " + e.getMessage(), e);
        }
        return result;
    }

    static String sanitizePath(String path) {
        if (path == null || path.isBlank()) return null;
        String normalized = path.replace("\\", "/").replaceAll("/+", "/");
        if (normalized.contains("..") || normalized.startsWith("/")) return null;
        return normalized;
    }

    String readFileContent(Path workspace, String relativePath, String headRef, List<String> warnings) {
        if (relativePath == null || !relativePath.equals(sanitizePath(relativePath))) return "";
        Path workspaceAbs = workspace.toAbsolutePath().normalize();
        Path fullPath = workspace.resolve(relativePath).normalize().toAbsolutePath();
        if (!fullPath.startsWith(workspaceAbs)) return "";
        try {
            if (Files.exists(fullPath)) {
                long sz = Files.size(fullPath);
                if (sz > MAX_FILE_SIZE_BYTES) {
                    warnings.add("File skipped (exceeds " + MAX_FILE_SIZE_BYTES + " bytes): " + relativePath);
                    return "";
                }
                return Files.readString(fullPath);
            }
            String gitPath = relativePath.replace("\\", "/");
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "show", headRef + ":" + gitPath);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            long timeout = gitTimeoutSeconds();
            GitCapture cap = runGitCapture(pb, timeout);
            if (cap.timedOut()) {
                warnings.add("git show timed out for: " + relativePath);
                return "";
            }
            if (cap.exitCode() != 0) {
                log.debug("git show failed for {} exit {}", relativePath, cap.exitCode());
                return "";
            }
            String content = cap.stdout() != null ? cap.stdout() : "";
            if (content.length() > MAX_FILE_SIZE_BYTES) {
                warnings.add("File skipped (exceeds " + MAX_FILE_SIZE_BYTES + " bytes): " + relativePath);
                return "";
            }
            return content;
        } catch (Exception e) {
            log.debug("Could not read file content: {}", relativePath, e);
            return "";
        }
    }
}
