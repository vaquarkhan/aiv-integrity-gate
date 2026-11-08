/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
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
        // Kill the whole tree: on Windows a wrapper (git.cmd, a credential helper, a pager) can outlive the
        // parent and keep the redirected output file open.
        java.util.List<ProcessHandle> tree = p.descendants().toList();
        tree.forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        // destroyForcibly is asynchronous; wait (bounded) until the tree is gone so it no longer holds the
        // working directory or the redirected output file.
        for (int i = 0; i < 50 && (p.isAlive() || tree.stream().anyMatch(ProcessHandle::isAlive)); i++) { LockSupport.parkNanos(100_000_000L); }
        return true;
    }

    /**
     * Best-effort temp-file cleanup. A timed-out child on Windows can still hold the handle for a moment; that
     * must not turn a clear "timed out" verdict into an unrelated file-locking error, so the file is deleted on
     * JVM exit instead.
     */
    static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (java.io.IOException e) {
            tmp.toFile().deleteOnExit();
        }
    }

    private record GitCapture(int exitCode, String stdout, boolean timedOut) {}

    record RawChange(String status, String oldPath, String newPath,
                             String oldMode, String newMode,
                             String oldObjectId, String newObjectId) {
        ChangedFile.ObjectType objectType() {
            if ("160000".equals(newMode)) {
                return ChangedFile.ObjectType.GITLINK;
            }
            if (newMode != null && !"000000".equals(newMode)) {
                return ChangedFile.ObjectType.BLOB;
            }
            return ChangedFile.ObjectType.UNKNOWN;
        }
    }

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
            long capturedBytes = Files.size(tmp);
            if (capturedBytes > maxGitCaptureBytes()) {
                throw new IllegalStateException("Git output exceeded capture limit of "
                        + maxGitCaptureBytes() + " bytes");
            }
            // Lenient UTF-8: binary patches (e.g. PNG) must not abort the whole gate.
            byte[] bytes = Files.readAllBytes(tmp);
            return new GitCapture(exit, new String(bytes, StandardCharsets.UTF_8), false);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024; // 2MB

    @Override
    public Diff getDiff(Path workspace, String baseRef, String headRef) {
        validateRef(baseRef);
        validateRef(headRef);
        String pinnedBase = resolveCommit(workspace, baseRef);
        String pinnedHead = resolveCommit(workspace, headRef);
        List<String> warnings = new ArrayList<>();
        String rawDiff = runGitDiffStrict(workspace, pinnedBase, pinnedHead);
        NumStatResult num = parseNumStatStrict(workspace, pinnedBase, pinnedHead);
        List<ChangedFile> files = parseChangedFiles(workspace, pinnedBase, pinnedHead, warnings);
        String author = parseAuthor(workspace, pinnedHead);
        boolean signed = parseHeadCommitSigned(workspace, pinnedHead);
        boolean skip = parseSkipDirectiveInLatestCommit(workspace, pinnedHead);
        return new Diff(pinnedBase, pinnedHead, files, rawDiff, num.added(), num.deleted(), author, signed, skip,
                warnings, num.perFileNet());
    }

    String resolveCommit(Path workspace, String ref) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "rev-parse", "--verify", ref);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            GitCapture cap = runGitCapture(pb, gitTimeoutSeconds());
            if (cap.timedOut() || cap.exitCode() != 0 || cap.stdout() == null || cap.stdout().isBlank()) {
                throw new IllegalStateException("Cannot resolve git ref to commit: " + ref);
            }
            String oid = cap.stdout().trim();
            if (!oid.matches("[0-9a-fA-F]{40,64}")) {
                throw new IllegalStateException("Invalid resolved commit OID for ref: " + ref);
            }
            return oid;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve git ref " + ref + ": " + e.getMessage(), e);
        }
    }

    private static void validateRef(String ref) {
        // VALID_REF allows '-' anywhere; a leading '-' would make git parse the positional ref as an option.
        if (ref == null || ref.isBlank() || ref.startsWith("-") || !VALID_REF.matcher(ref).matches()) {
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
            // Only G (good, valid signature) counts. U = good but untrusted key, X = expired, Y = expired key,
            // R = revoked key, E = cannot check, N = unsigned. Anything but G is not identity evidence.
            return "G".equals(status);
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
            return cap.stdout();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("git diff failed: " + e.getMessage(), e);
        }
    }

    List<ChangedFile> parseChangedFiles(Path workspace, String baseRef, String headRef, List<String> warnings) {
        Map<String, RawChange> rawChanges = parseRawChanges(workspace, baseRef, headRef);
        List<ChangedFile> result = new ArrayList<>();
        for (RawChange raw : rawChanges.values()) {
            String path = raw.newPath() != null ? raw.newPath() : raw.oldPath();
            ChangedFile.ChangeType type = changeType(raw.status());
            int warningsBefore = warnings.size();
            String content = type == ChangedFile.ChangeType.DELETED || raw.objectType() == ChangedFile.ObjectType.GITLINK
                    ? "" : readFileContent(workspace, path, headRef, warnings);
            ChangedFile.SourceCompleteness completeness = warnings.size() == warningsBefore
                    ? ChangedFile.SourceCompleteness.COMPLETE : ChangedFile.SourceCompleteness.TRUNCATED;
            result.add(new ChangedFile(path, type, content,
                    raw.oldPath(), raw.oldMode(), raw.newMode(), raw.oldObjectId(), raw.newObjectId(),
                    raw.objectType(), completeness,
                    ChangedFile.Provenance.TRUSTED_GIT_ADAPTER));
        }
        return result;
    }

    static ChangedFile.ChangeType changeType(String status) {
        return switch (status.charAt(0)) {
            case 'A' -> ChangedFile.ChangeType.ADDED;
            case 'D' -> ChangedFile.ChangeType.DELETED;
            case 'R' -> ChangedFile.ChangeType.RENAMED;
            case 'C' -> ChangedFile.ChangeType.COPIED;
            default -> ChangedFile.ChangeType.MODIFIED;
        };
    }

    /** Parse NUL-delimited raw diff records so paths, object IDs, and Git modes stay unambiguous. */
    Map<String, RawChange> parseRawChanges(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "diff", "--raw", "--no-abbrev", "-z",
                    baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            GitCapture cap = runGitCapture(pb, gitTimeoutSeconds());
            if (cap.timedOut()) {
                throw new IllegalStateException("git diff --raw timed out");
            }
            if (cap.exitCode() != 0) {
                throw new IllegalStateException("git diff --raw failed with exit " + cap.exitCode());
            }
            return parseRawOutput(cap.stdout());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("git diff --raw failed: " + e.getMessage(), e);
        }
    }

    static Map<String, RawChange> parseRawOutput(String output) {
        Map<String, RawChange> changes = new java.util.LinkedHashMap<>();
        String[] tokens = (output == null ? "" : output)
                .split(Pattern.quote(Character.toString(0)), -1);
        int i = 0;
        while (i < tokens.length && !tokens[i].isEmpty()) {
            String header = tokens[i++];
            if (!header.startsWith(":")) {
                throw new IllegalStateException("Unexpected git diff --raw record");
            }
            String[] meta = header.substring(1).trim().split("\\s+");
            if (meta.length != 5) {
                throw new IllegalStateException("Invalid git diff --raw metadata");
            }
            if (i >= tokens.length) {
                throw new IllegalStateException("Missing path in git diff --raw record");
            }
            String status = meta[4];
            String firstPath = sanitizePath(tokens[i++]);
            if (firstPath == null) {
                throw new IllegalStateException("Unsafe path in git diff --raw record");
            }
            String oldPath = firstPath;
            String newPath = firstPath;
            if (status.startsWith("R") || status.startsWith("C")) {
                if (i >= tokens.length) {
                    throw new IllegalStateException("Missing destination path in git diff --raw record");
                }
                newPath = sanitizePath(tokens[i++]);
                if (newPath == null) {
                    throw new IllegalStateException("Unsafe destination path in git diff --raw record");
                }
            } else if (status.startsWith("A")) {
                oldPath = null;
            } else if (status.startsWith("D")) {
                newPath = null;
            }
            String key = newPath != null ? newPath : oldPath;
            if (key != null) {
                changes.put(key, new RawChange(status, oldPath, newPath,
                        meta[0], meta[1], meta[2], meta[3]));
            }
        }
        return changes;
    }

    static String sanitizePath(String path) {
        if (path == null || path.isBlank()) return null;
        String normalized = path.replace("\\", "/").replaceAll("/+", "/");
        boolean parentSegment = java.util.Arrays.stream(normalized.split("/"))
                .anyMatch(".."::equals);
        if (parentSegment || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) return null;
        return normalized;
    }

    String readFileContent(Path workspace, String relativePath, String headRef, List<String> warnings) {
        if (relativePath == null || !relativePath.equals(sanitizePath(relativePath))) {
            throw new IllegalStateException("Unsafe Git path: " + relativePath);
        }
        try {
            String gitPath = relativePath.replace("\\", "/");
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "show", headRef + ":" + gitPath);
            pb.directory(workspace.toFile());
            configureGitChild(pb);
            GitCapture cap = runGitCapture(pb, gitTimeoutSeconds());
            if (cap.timedOut()) {
                throw new IllegalStateException("git show timed out for: " + relativePath);
            }
            if (cap.exitCode() != 0 || cap.stdout() == null) {
                throw new IllegalStateException("Cannot read file from pinned head: " + relativePath);
            }
            String content = cap.stdout();
            if (content.length() > MAX_FILE_SIZE_BYTES) {
                throw new IllegalStateException("File exceeds " + MAX_FILE_SIZE_BYTES + " bytes: " + relativePath);
            }
            if (looksBinary(content.getBytes(StandardCharsets.UTF_8))) {
                warnings.add("Binary file content not scanned: " + relativePath);
                return "";
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read file from pinned head " + relativePath + ": "
                    + e.getMessage(), e);
        }
    }

    /** Null byte in the first 8KiB ⇒ treat as binary (git-style heuristic). */
    static boolean looksBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        int n = Math.min(bytes.length, 8192);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
