/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.git;

import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
/**
 * @author Vaquar Khan
 */
class GitDiffProviderTest {

    private static final String TIMEOUT_PROP = "aiv.git.timeout.seconds";
    private static String previousTimeout;

    @BeforeAll
    static void tightenGitTimeoutForTests() {
        previousTimeout = System.getProperty(TIMEOUT_PROP);
        System.setProperty(TIMEOUT_PROP, "60");
    }

    @AfterAll
    static void restoreGitTimeout() {
        if (previousTimeout == null) {
            System.clearProperty(TIMEOUT_PROP);
        } else {
            System.setProperty(TIMEOUT_PROP, previousTimeout);
        }
    }

    @Test
    void getDiffReturnsDiff(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), "HEAD", "HEAD");
        assertNotNull(diff);
        assertEquals(runGit(repo, "rev-parse", "HEAD").trim(), diff.getBaseRef());
        assertEquals(runGit(repo, "rev-parse", "HEAD").trim(), diff.getHeadRef());
        assertNotNull(diff.getChangedFiles());
        assertNotNull(diff.getRawDiff());
    }

    @Test
    void rawParserCoversStatusesAndRejectsMalformedRecords() {
        String nul = Character.toString(0);
        String sha = "1".repeat(40);
        String zero = "0".repeat(40);
        String raw = ":000000 100644 " + zero + " " + sha + " A" + nul + "added.txt" + nul
                + ":100644 000000 " + sha + " " + zero + " D" + nul + "deleted.txt" + nul
                + ":100644 100644 " + sha + " " + sha + " R100" + nul + "old.txt" + nul + "new.txt" + nul
                + ":100644 100644 " + sha + " " + sha + " C100" + nul + "source.txt" + nul + "copy.txt" + nul;
        Map<String, GitDiffProvider.RawChange> parsed = GitDiffProvider.parseRawOutput(raw);
        assertEquals(4, parsed.size());
        assertEquals("old.txt", parsed.get("new.txt").oldPath());
        assertEquals(ChangedFile.ChangeType.ADDED, GitDiffProvider.changeType("A"));
        assertEquals(ChangedFile.ChangeType.DELETED, GitDiffProvider.changeType("D"));
        assertEquals(ChangedFile.ChangeType.RENAMED, GitDiffProvider.changeType("R100"));
        assertEquals(ChangedFile.ChangeType.COPIED, GitDiffProvider.changeType("C100"));
        assertEquals(ChangedFile.ChangeType.MODIFIED, GitDiffProvider.changeType("M"));
        assertEquals(ChangedFile.ObjectType.BLOB, parsed.get("added.txt").objectType());
        assertEquals(ChangedFile.ObjectType.UNKNOWN, parsed.get("deleted.txt").objectType());
        assertTrue(GitDiffProvider.parseRawOutput(null).isEmpty());
        assertThrows(IllegalStateException.class, () -> GitDiffProvider.parseRawOutput("bad" + nul));
        assertThrows(IllegalStateException.class, () -> GitDiffProvider.parseRawOutput(":bad" + nul));
        assertThrows(IllegalStateException.class, () -> GitDiffProvider.parseRawOutput(
                ":100644 100644 " + sha + " " + sha + " M"));
        assertThrows(IllegalStateException.class, () -> GitDiffProvider.parseRawOutput(
                ":100644 100644 " + sha + " " + sha + " R100" + nul + "old.txt"));
    }

    @Test
    void getDiffWithUnknownRefFailsClosed(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        var provider = new GitDiffProvider();
        assertThrows(IllegalStateException.class, () ->
                provider.getDiff(repo.toAbsolutePath(), "nonexistent-ref-xyz", "HEAD"));
    }

    @Test
    void getDiffWithMaliciousRefThrows() {
        var provider = new GitDiffProvider();
        Path workspace = Path.of(".").toAbsolutePath();
        assertThrows(IllegalArgumentException.class, () ->
                provider.getDiff(workspace, "; rm -rf /", "HEAD"));
        assertThrows(IllegalArgumentException.class, () ->
                provider.getDiff(workspace, "origin/main", "$(whoami)"));
    }

    @Test
    void getDiffRejectsRefsThatGitWouldParseAsOptions() {
        var provider = new GitDiffProvider();
        Path workspace = Path.of(".").toAbsolutePath();
        // '-' is a valid ref character, so these match VALID_REF; the leading-dash check must reject them.
        assertThrows(IllegalArgumentException.class, () -> provider.getDiff(workspace, "-p", "HEAD"));
        assertThrows(IllegalArgumentException.class, () -> provider.getDiff(workspace, "HEAD", "--all"));
    }

    @Test
    void parsesAuthorLocAndChangedFiles(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        // base commit
        Files.writeString(repo.resolve("a.txt"), "one\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("del.txt"), "delete-me\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "a.txt", "del.txt");
        runGit(repo, "commit", "-m", "init");
        String base = runGit(repo, "rev-parse", "HEAD").trim();

        // next commit: rename + modify, add, delete
        runGit(repo, "mv", "a.txt", "a-renamed.txt");
        Files.writeString(repo.resolve("a-renamed.txt"), "one\ntwo\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("b.txt"), "new\n", StandardCharsets.UTF_8);
        runGit(repo, "rm", "del.txt");
        runGit(repo, "add", "a-renamed.txt", "b.txt");
        runGit(repo, "commit", "-m", "change");

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), base, "HEAD");

        assertEquals(base, diff.getBaseRef());
        assertEquals(runGit(repo, "rev-parse", "HEAD").trim(), diff.getHeadRef());
        assertEquals("test@example.com", diff.getAuthorEmail());
        assertFalse(diff.isHeadCommitSigned());
        assertTrue(diff.getLinesAdded() >= 1);
        assertTrue(diff.getLinesDeleted() >= 1);

        List<String> paths = diff.getChangedFiles().stream().map(ChangedFile::getPath).toList();
        assertTrue(paths.contains("a-renamed.txt"));
        assertTrue(paths.contains("b.txt"));
        assertTrue(paths.contains("del.txt"));
        ChangedFile renamed = diff.getChangedFiles().stream()
                .filter(f -> "a-renamed.txt".equals(f.getPath())).findFirst().orElseThrow();
        assertEquals(ChangedFile.ChangeType.RENAMED, renamed.getChangeType());
        assertEquals("a.txt", renamed.getOldPath());
        assertEquals(ChangedFile.Provenance.TRUSTED_GIT_ADAPTER, renamed.getProvenance());
        ChangedFile deleted = diff.getChangedFiles().stream()
                .filter(f -> "del.txt".equals(f.getPath())).findFirst().orElseThrow();
        assertEquals(ChangedFile.ChangeType.DELETED, deleted.getChangeType());
        assertEquals("000000", deleted.getNewMode());
    }

    @Test
    void capturesAuthoritativeGitlinkModeAndObject(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Files.writeString(repo.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "base.txt");
        runGit(repo, "commit", "-m", "base");
        String base = runGit(repo, "rev-parse", "HEAD").trim();

        runGit(repo, "update-index", "--add", "--cacheinfo", "160000," + base + ",airflow");
        runGit(repo, "commit", "-m", "add accidental gitlink");

        Diff diff = new GitDiffProvider().getDiff(repo.toAbsolutePath(), base, "HEAD");
        ChangedFile gitlink = diff.getChangedFiles().stream()
                .filter(f -> "airflow".equals(f.getPath())).findFirst().orElseThrow();
        assertEquals(ChangedFile.ChangeType.ADDED, gitlink.getChangeType());
        assertEquals("000000", gitlink.getOldMode());
        assertEquals("160000", gitlink.getNewMode());
        assertEquals(base, gitlink.getNewObjectId());
        assertEquals(ChangedFile.ObjectType.GITLINK, gitlink.getObjectType());
        assertEquals(ChangedFile.Provenance.TRUSTED_GIT_ADAPTER, gitlink.getProvenance());
        assertTrue(gitlink.isNewGitlink());
    }

    @Test
    void detectsSkipRequestedFromCommitMessage(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        Files.writeString(repo.resolve("x.txt"), "x\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "x.txt");
        runGit(repo, "commit", "-m", "base");
        String base = runGit(repo, "rev-parse", "HEAD").trim();

        Files.writeString(repo.resolve("x.txt"), "x\ny\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "x.txt");
        runGit(repo, "commit", "-m", "/aiv skip");

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), base, "HEAD");
        assertTrue(diff.isSkipDirectivePresent());
    }

    @Test
    void skipDirectiveDoesNotMatchSubstringInProse(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        Files.writeString(repo.resolve("x.txt"), "x\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "x.txt");
        runGit(repo, "commit", "-m", "base");
        String base = runGit(repo, "rev-parse", "HEAD").trim();

        Files.writeString(repo.resolve("x.txt"), "x\ny\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "x.txt");
        runGit(repo, "commit", "-m", "docs: explain aiv skip workflow for maintainers");

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), base, "HEAD");
        assertFalse(diff.isSkipDirectivePresent());
    }

    @Test
    void readsContentFromGitShowWhenWorkingTreeFileIsMissing(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        Files.writeString(repo.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "base.txt");
        runGit(repo, "commit", "-m", "base");
        String base = runGit(repo, "rev-parse", "HEAD").trim();

        Files.writeString(repo.resolve("show.txt"), "from-git-show\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "show.txt");
        runGit(repo, "commit", "-m", "add show.txt");

        // Remove from working tree so provider is forced down the `git show` path.
        Files.delete(repo.resolve("show.txt"));

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), base, "HEAD");

        ChangedFile cf = diff.getChangedFiles().stream()
                .filter(f -> "show.txt".equals(f.getPath()))
                .findFirst()
                .orElseThrow();
        assertFalse(cf.getContent().isEmpty());
        assertTrue(cf.getContent().contains("from-git-show"));
    }

    @Test
    void skipsUnsafePathsAndTruncatesVeryLargeFiles(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        Files.writeString(repo.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "a.txt");
        runGit(repo, "commit", "-m", "base");
        String base = runGit(repo, "rev-parse", "HEAD").trim();

        // Repeated dots inside a filename are valid and must remain visible to gates.
        Files.writeString(repo.resolve("foo..bar.txt"), "x\n", StandardCharsets.UTF_8);

        // Large file (> 2MB) should be included but have empty content.
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        new Random(1).nextBytes(big);
        Files.write(repo.resolve("big.txt"), big);

        runGit(repo, "add", "foo..bar.txt", "big.txt");
        runGit(repo, "commit", "-m", "add unsafe and big");

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), base, "HEAD");

        List<String> paths = diff.getChangedFiles().stream().map(ChangedFile::getPath).toList();
        assertTrue(paths.contains("foo..bar.txt"));

        ChangedFile bigFile = diff.getChangedFiles().stream()
                .filter(f -> "big.txt".equals(f.getPath()))
                .findFirst()
                .orElseThrow();
        assertTrue(bigFile.getContent().isEmpty());
        assertTrue(diff.getWarnings().stream().anyMatch(w -> w.contains("big.txt")));
    }

    @Test
    void binaryFilesDoNotBreakNumstatParsing(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        Files.writeString(repo.resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "a.txt");
        runGit(repo, "commit", "-m", "base");
        String base = runGit(repo, "rev-parse", "HEAD").trim();

        // Binary file should produce '-' entries in `git diff --numstat` on some platforms.
        Files.write(repo.resolve("bin.dat"), new byte[] {0, 1, 2, 3, 4, 5, 0, 9});
        runGit(repo, "add", "bin.dat");
        runGit(repo, "commit", "-m", "add binary");

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), base, "HEAD");
        assertNotNull(diff);
        assertTrue(diff.getLinesAdded() >= 0);
        assertTrue(diff.getLinesDeleted() >= 0);
        assertTrue(GitDiffProvider.looksBinary(new byte[] {0, 1, 2}));
        assertFalse(GitDiffProvider.looksBinary("hello\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(GitDiffProvider.looksBinary(null));
        assertFalse(GitDiffProvider.looksBinary(new byte[0]));
    }

    @Test
    void readFileContentRejectsUnsanitizedOrEscapingPaths(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        var provider = new GitDiffProvider();
        var w = new ArrayList<String>();

        assertThrows(IllegalStateException.class, () ->
                provider.readFileContent(repo.toAbsolutePath(), "a\\b.txt", "HEAD", w));

        // Absolute paths must fail closed rather than be treated as empty content.
        assertThrows(IllegalStateException.class, () ->
                provider.readFileContent(repo.toAbsolutePath(), "C:/Windows/System32/drivers/etc/hosts", "HEAD", w));
    }

    @Test
    void readFileContentRejectsAbsolutePathOutsideWorkspace(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        var provider = new GitDiffProvider();
        var w = new ArrayList<String>();

        Path outside = Files.createTempFile("aiv-outside-", ".txt").toAbsolutePath();
        try {
            // This absolute path is guaranteed to be rejected rather than treated as empty content.
            assertThrows(IllegalStateException.class, () ->
                    provider.readFileContent(repo.toAbsolutePath(), outside.toString(), "HEAD", w));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void gitHelpersGracefullyHandleMissingWorkspace() throws Exception {
        var provider = new GitDiffProvider();
        Path missing = Path.of("this-directory-should-not-exist-12345").toAbsolutePath().resolve("missing");

        assertNull(provider.parseAuthor(missing, "HEAD"));
    }

    @Test
    void helpersCoverRemainingBranches(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        // sanitizePath: null/blank branches
        assertNull(GitDiffProvider.sanitizePath(null));
        assertNull(GitDiffProvider.sanitizePath("   "));
        assertNull(GitDiffProvider.sanitizePath("a/../b.py"));
        assertEquals("foo..bar.py", GitDiffProvider.sanitizePath("foo..bar.py"));

        // readFileContent: an unavailable repository must fail closed.
        Path missing = repo.resolve("does-not-exist");
        var warn = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () ->
                new GitDiffProvider().readFileContent(missing, "x.txt", "HEAD", warn));

        // readFileContent: `git show` content length > 2MB should return empty string.
        Files.writeString(repo.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "base.txt");
        runGit(repo, "commit", "-m", "base");

        String huge = "a".repeat(2 * 1024 * 1024 + 10);
        Files.writeString(repo.resolve("huge.txt"), huge, StandardCharsets.UTF_8);
        runGit(repo, "add", "huge.txt");
        runGit(repo, "commit", "-m", "add huge");
        Files.delete(repo.resolve("huge.txt"));

        warn.clear();
        assertThrows(IllegalStateException.class, () ->
                new GitDiffProvider().readFileContent(repo.toAbsolutePath(), "huge.txt", "HEAD", warn));
    }

    @Test
    void gitTimeoutSecondsReadsPropertyOrDefault() throws Exception {
        String prop = "aiv.git.timeout.seconds";
        String prev = System.getProperty(prop);
        try {
            System.clearProperty(prop);
            assertEquals(120L, GitDiffProvider.gitTimeoutSeconds());
            System.setProperty(prop, "  ");
            assertEquals(120L, GitDiffProvider.gitTimeoutSeconds());
            System.setProperty(prop, "  3 ");
            assertEquals(3L, GitDiffProvider.gitTimeoutSeconds());
            System.setProperty(prop, "-9");
            assertEquals(120L, GitDiffProvider.gitTimeoutSeconds());
            System.setProperty(prop, "not-a-number");
            assertEquals(120L, GitDiffProvider.gitTimeoutSeconds());
        } finally {
            if (prev == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, prev);
            }
        }
    }

    @Test
    void maxGitCaptureBytesReadsPropertyOrDefault() throws Exception {
        String prop = "aiv.git.capture.max.bytes";
        String prev = System.getProperty(prop);
        try {
            System.clearProperty(prop);
            assertEquals(64L * 1024 * 1024, GitDiffProvider.maxGitCaptureBytes());
            System.setProperty(prop, "1024");
            assertEquals(1024L, GitDiffProvider.maxGitCaptureBytes());
            System.setProperty(prop, "   ");
            assertEquals(64L * 1024 * 1024, GitDiffProvider.maxGitCaptureBytes());
            System.setProperty(prop, "0");
            assertEquals(64L * 1024 * 1024, GitDiffProvider.maxGitCaptureBytes());
            System.setProperty(prop, "bad");
            assertEquals(64L * 1024 * 1024, GitDiffProvider.maxGitCaptureBytes());
        } finally {
            if (prev == null) {
                System.clearProperty(prop);
            } else {
                System.setProperty(prop, prev);
            }
        }
    }

    @Test
    void gitProcessTimedOutWithZeroTimeoutWaitsUntilExit() throws Exception {
        ProcessBuilder pb = quickExitProcess();
        Process p = pb.start();
        assertFalse(GitDiffProvider.gitProcessTimedOut(p, 0L));
    }

    @Test
    void gitProcessTimedOutClampsNonPositiveTimeoutToOneSecond() throws Exception {
        ProcessBuilder pb = quickExitProcess();
        Process p = pb.start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertFalse(GitDiffProvider.gitProcessTimedOut(p, -5L));
    }

    @Test
    void gitProcessTimedOutDestroysHungProcess(@TempDir Path dir) throws Exception {
        Path tmp = dir.resolve("hung" + slowGitExtension());
        try {
            writeHungProcessScript(tmp);
            long t0 = System.nanoTime();
            ProcessBuilder hangPb;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                hangPb = new ProcessBuilder(tmp.toAbsolutePath().toString());
            } else {
                hangPb = new ProcessBuilder("sh", tmp.toAbsolutePath().toString());
            }
            Process p = hangPb.start();
            assertTrue(GitDiffProvider.gitProcessTimedOut(p, 1L));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 15_000);
            p.waitFor(5, TimeUnit.SECONDS);
        } finally {
            for (int i = 0; i < 30; i++) {
                try {
                    Files.deleteIfExists(tmp);
                    break;
                } catch (java.nio.file.FileSystemException e) {
                    Thread.sleep(100);
                }
            }
        }
    }

    @Test
    void runGitFailsClosedWhenCaptureLimitExceeded(@TempDir Path repo) throws Exception {
        initRepo(repo);
        String cap = "aiv.git.capture.max.bytes";
        String prevCap = System.getProperty(cap);
        try {
            System.setProperty(cap, "2");
            Files.writeString(repo.resolve("z.txt"), "abcd\n", StandardCharsets.UTF_8);
            runGit(repo, "add", "z.txt");
            runGit(repo, "commit", "-m", "z");
            String base = runGit(repo, "rev-parse", "HEAD~1").trim();
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().getDiff(repo.toAbsolutePath(), base, "HEAD"));
        } finally {
            if (prevCap == null) {
                System.clearProperty(cap);
            } else {
                System.setProperty(cap, prevCap);
            }
        }
    }

    @Test
    void resolveCommitRejectsInvalidOidAndWrapsLaunchFailure(@TempDir Path repo) throws Exception {
        initRepo(repo);
        String previous = System.getProperty("aiv.git.executable");
        try {
            Path bad = writeDelegatingGitStub(repo, Map.of("badRevParse", true));
            System.setProperty("aiv.git.executable", bad.toAbsolutePath().toString());
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().resolveCommit(repo, "HEAD"));
            System.setProperty("aiv.git.executable", "missing-git-" + System.nanoTime() + ".exe");
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().resolveCommit(repo, "HEAD"));
        } finally {
            if (previous == null) System.clearProperty("aiv.git.executable");
            else System.setProperty("aiv.git.executable", previous);
        }
    }

    @Test
    void plainDiffFailureAndTimeoutFailClosed(@TempDir Path repo) throws Exception {
        initRepo(repo);
        String previousGit = System.getProperty("aiv.git.executable");
        String previousTimeout = System.getProperty(TIMEOUT_PROP);
        try {
            Path fail = writeDelegatingGitStub(repo, Map.of("failPlainDiff", true));
            System.setProperty("aiv.git.executable", fail.toAbsolutePath().toString());
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().runGitDiffStrict(repo, "HEAD", "HEAD"));
            Path hang = writeDelegatingGitStub(repo, Map.of("hangOnPlainDiff", true));
            System.setProperty("aiv.git.executable", hang.toAbsolutePath().toString());
            System.setProperty(TIMEOUT_PROP, "1");
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().runGitDiffStrict(repo, "HEAD", "HEAD"));
            assertTrue(error.getMessage().contains("timed out"));
        } finally {
            if (previousGit == null) System.clearProperty("aiv.git.executable");
            else System.setProperty("aiv.git.executable", previousGit);
            if (previousTimeout == null) System.clearProperty(TIMEOUT_PROP);
            else System.setProperty(TIMEOUT_PROP, previousTimeout);
        }
    }

    @Test
    void parseNumIgnoresNonNumericTokens() throws Exception {
        var provider = new GitDiffProvider();
        assertEquals(0, provider.parseNum("x"));
    }

    @Test
    void parseNumHandlesNullAndDashAsZero() throws Exception {
        var provider = new GitDiffProvider();
        assertEquals(0, provider.parseNum(null));
        assertEquals(0, provider.parseNum("-"));
    }

    @Test
    void getDiffFailsClosedWhenNumstatFails(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("failNumstat", true));
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", stub.toAbsolutePath().toString());
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().getDiff(repo.toAbsolutePath(), "HEAD", "HEAD"));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void getDiffFailsClosedWhenRawFails(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("failRaw", true));
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", stub.toAbsolutePath().toString());
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().getDiff(repo.toAbsolutePath(), "HEAD", "HEAD"));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void getDiffFailsClosedWhenNumstatTimesOut(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("hangOnNumstat", true));
        String prevGit = System.getProperty("aiv.git.executable");
        String prevTimeout = System.getProperty(TIMEOUT_PROP);
        try {
            System.setProperty("aiv.git.executable", stub.toAbsolutePath().toString());
            System.setProperty(TIMEOUT_PROP, "1");
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().getDiff(repo.toAbsolutePath(), "HEAD", "HEAD"));
            assertTrue(ex.getMessage().contains("timed out"), ex::getMessage);
        } finally {
            if (prevGit == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prevGit);
            }
            if (prevTimeout == null) {
                System.clearProperty(TIMEOUT_PROP);
            } else {
                System.setProperty(TIMEOUT_PROP, prevTimeout);
            }
        }
    }

    @Test
    void getDiffFailsClosedWhenRawTimesOut(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("hangOnRaw", true));
        String prevGit = System.getProperty("aiv.git.executable");
        String prevTimeout = System.getProperty(TIMEOUT_PROP);
        try {
            System.setProperty("aiv.git.executable", stub.toAbsolutePath().toString());
            System.setProperty(TIMEOUT_PROP, "1");
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().getDiff(repo.toAbsolutePath(), "HEAD", "HEAD"));
            assertTrue(ex.getMessage().contains("timed out"), ex::getMessage);
        } finally {
            if (prevGit == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prevGit);
            }
            if (prevTimeout == null) {
                System.clearProperty(TIMEOUT_PROP);
            } else {
                System.setProperty(TIMEOUT_PROP, prevTimeout);
            }
        }
    }

    @Test
    void toleratesAuthorAndSkipLogFailures(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("failLogFormat", true, "failLogPretty", true));
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", stub.toAbsolutePath().toString());
            Diff diff = new GitDiffProvider().getDiff(repo.toAbsolutePath(), "HEAD", "HEAD");
            assertNotNull(diff);
            assertNull(diff.getAuthorEmail());
            assertFalse(diff.isHeadCommitSigned());
            assertFalse(diff.isSkipDirectivePresent());
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void parseNumStatStrictWrapsIOException() throws Exception {
        var provider = new GitDiffProvider();
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", Path.of("no-such-git-" + System.nanoTime() + ".exe").toString());
            assertThrows(IllegalStateException.class, () ->
                    provider.parseNumStatStrict(Path.of(".").toAbsolutePath(), "HEAD", "HEAD"));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void parseSkipReturnsFalseWhenGitCannotStart(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", repo.resolve("missing-git-" + System.nanoTime()).toString());
            assertFalse(new GitDiffProvider().parseSkipDirectiveInLatestCommit(repo.toAbsolutePath(), "HEAD"));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void onlyGoodSignatureCountsAsSigned(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", writeDelegatingGitStub(repo, Map.of("sigG", true)).toAbsolutePath().toString());
            assertTrue(new GitDiffProvider().parseHeadCommitSigned(repo.toAbsolutePath(), "HEAD"));
            // U (good signature, untrusted key), X/Y/R (expired/revoked) are not identity evidence.
            System.setProperty("aiv.git.executable", writeDelegatingGitStub(repo, Map.of("sigU", true)).toAbsolutePath().toString());
            assertFalse(new GitDiffProvider().parseHeadCommitSigned(repo.toAbsolutePath(), "HEAD"));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void parseHeadCommitSignedReturnsFalseWhenGitCannotStart(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", repo.resolve("missing-git-" + System.nanoTime()).toString());
            assertFalse(new GitDiffProvider().parseHeadCommitSigned(repo.toAbsolutePath(), "HEAD"));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void runGitDiffStrictWrapsIOException() throws Exception {
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", Path.of("missing-git-" + System.nanoTime() + ".exe").toString());
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().runGitDiffStrict(Path.of(".").toAbsolutePath(), "HEAD", "HEAD"));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void parseChangedFilesWrapsIOException() throws Exception {
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", Path.of("missing-git-" + System.nanoTime() + ".exe").toString());
            assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().parseChangedFiles(Path.of(".").toAbsolutePath(), "HEAD", "HEAD", new ArrayList<>()));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void readFileContentFailsClosedWhenGitShowTimesOut(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("hangOnShow", true));
        String prevGit = System.getProperty("aiv.git.executable");
        String prevTimeout = System.getProperty(TIMEOUT_PROP);
        try {
            System.setProperty("aiv.git.executable", stub.toAbsolutePath().toString());
            System.setProperty(TIMEOUT_PROP, "1");
            Files.writeString(repo.resolve("only-in-git.txt"), "c\n", StandardCharsets.UTF_8);
            runGit(repo, "add", "only-in-git.txt");
            runGit(repo, "commit", "-m", "add");
            Files.delete(repo.resolve("only-in-git.txt"));
            var warn = new ArrayList<String>();
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    new GitDiffProvider().readFileContent(repo.toAbsolutePath(), "only-in-git.txt", "HEAD", warn));
            assertTrue(error.getMessage().contains("timed out"));
        } finally {
            if (prevGit == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prevGit);
            }
            if (prevTimeout == null) {
                System.clearProperty(TIMEOUT_PROP);
            } else {
                System.setProperty(TIMEOUT_PROP, prevTimeout);
            }
        }
    }

    @Test
    void readFileContentFailsClosedWhenGitShowFails(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        var provider = new GitDiffProvider();
        Files.writeString(repo.resolve("x.txt"), "x\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "x.txt");
        runGit(repo, "commit", "-m", "x");
        Files.delete(repo.resolve("x.txt"));
        var warn = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () ->
                provider.readFileContent(repo.toAbsolutePath(), "x.txt", "not-a-valid-ref-zzz", warn));
    }

    private static String findGitExecutable() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = win ? new ProcessBuilder("where", "git") : new ProcessBuilder("sh", "-c", "command -v git");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        assertEquals(0, p.waitFor(), "git must be on PATH for adapter tests");
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            assertNotNull(line);
            return line.trim();
        }
    }

    /**
     * Writes a small wrapper around the real {@code git} so tests can force failures for specific subcommands.
     */
    private static Path writeDelegatingGitStub(Path dir, Map<String, Boolean> modes) throws Exception {
        String realGit = findGitExecutable().replace("\\", "/");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path stub = dir.resolve(win ? "git-stub.cmd" : "git-stub.sh");
        boolean failNumstat = Boolean.TRUE.equals(modes.get("failNumstat"));
        boolean failRaw = Boolean.TRUE.equals(modes.get("failRaw"));
        boolean hangOnNumstat = Boolean.TRUE.equals(modes.get("hangOnNumstat"));
        boolean hangOnRaw = Boolean.TRUE.equals(modes.get("hangOnRaw"));
        boolean failLogFormat = Boolean.TRUE.equals(modes.get("failLogFormat"));
        boolean failLogPretty = Boolean.TRUE.equals(modes.get("failLogPretty"));
        boolean hangOnShow = Boolean.TRUE.equals(modes.get("hangOnShow"));
        boolean failPlainDiff = Boolean.TRUE.equals(modes.get("failPlainDiff"));
        boolean hangOnPlainDiff = Boolean.TRUE.equals(modes.get("hangOnPlainDiff"));
        boolean badRevParse = Boolean.TRUE.equals(modes.get("badRevParse"));
        String sig = Boolean.TRUE.equals(modes.get("sigG")) ? "G" : Boolean.TRUE.equals(modes.get("sigU")) ? "U" : null;
        if (win) {
            StringBuilder sb = new StringBuilder();
            sb.append("@echo off\n");
            sb.append("set \"REAL_GIT=").append(realGit).append("\"\n");
            if (sig != null) {
                // cmd splits on '=': "--format=%G?" arrives as %~3 == --format and %~4 == %G?
                sb.append("if /I \"%~3\"==\"--format\" if \"%~4\"==\"%%G?\" (echo ").append(sig).append("& exit /b 0)\n");
            }
            if (badRevParse) {
                sb.append("if /I \"%~1\"==\"rev-parse\" (echo not-an-oid& exit /b 0)\n");
            }
            if (failPlainDiff) {
                sb.append("if /I \"%~1\"==\"diff\" if /I not \"%~2\"==\"--raw\" if /I not \"%~2\"==\"--numstat\" exit /b 1\n");
            }
            if (hangOnPlainDiff) {
                sb.append("if /I \"%~1\"==\"diff\" if /I not \"%~2\"==\"--raw\" if /I not \"%~2\"==\"--numstat\" (\n  :hangplain\n  goto hangplain\n)\n");
            }
            if (failNumstat) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--numstat\" exit /b 1\n");
            }
            if (failRaw) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--raw\" exit /b 1\n");
            }
            if (hangOnNumstat) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--numstat\" (\n");
                sb.append("  :hangnum\n");
                sb.append("  goto hangnum\n");
                sb.append(")\n");
            }
            if (hangOnRaw) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--raw\" (\n");
                sb.append("  :hangns\n");
                sb.append("  goto hangns\n");
                sb.append(")\n");
            }
            if (failLogFormat) {
                sb.append("if /I \"%~1\"==\"log\" echo %~3 | findstr /I \"format\" >nul && exit /b 1\n");
            }
            if (failLogPretty) {
                sb.append("if /I \"%~1\"==\"log\" echo %~3 | findstr /I \"pretty\" >nul && exit /b 1\n");
            }
            if (hangOnShow) {
                sb.append("if /I \"%~1\"==\"show\" (\n");
                sb.append("  :loop\n");
                sb.append("  goto loop\n");
                sb.append(")\n");
            }
            sb.append("\"%REAL_GIT%\" %*\n");
            Files.writeString(stub, sb.toString(), StandardCharsets.UTF_8);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("#!/bin/sh\n");
            sb.append("REAL_GIT='").append(realGit.replace("'", "'\\''")).append("'\n");
            if (sig != null) {
                sb.append("if [ \"$1\" = log ] && [ \"$3\" = '--format=%G?' ]; then echo ").append(sig).append("; exit 0; fi\n");
            }
            if (badRevParse) {
                sb.append("if [ \"$1\" = rev-parse ]; then echo not-an-oid; exit 0; fi\n");
            }
            if (failPlainDiff) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" != --raw ] && [ \"$2\" != --numstat ]; then exit 1; fi\n");
            }
            if (hangOnPlainDiff) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" != --raw ] && [ \"$2\" != --numstat ]; then while true; do :; done; fi\n");
            }
            if (failNumstat) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --numstat ]; then exit 1; fi\n");
            }
            if (failRaw) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --raw ]; then exit 1; fi\n");
            }
            if (hangOnNumstat) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --numstat ]; then while true; do :; done; fi\n");
            }
            if (hangOnRaw) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --raw ]; then while true; do :; done; fi\n");
            }
            if (failLogFormat) {
                sb.append("if [ \"$1\" = log ] && [ \"$3\" = --format=%ae ]; then exit 1; fi\n");
            }
            if (failLogPretty) {
                sb.append("if [ \"$1\" = log ] && [ \"$3\" = --pretty=%B ]; then exit 1; fi\n");
            }
            if (hangOnShow) {
                sb.append("if [ \"$1\" = show ]; then while true; do :; done; fi\n");
            }
            sb.append("exec \"$REAL_GIT\" \"$@\"\n");
            Files.writeString(stub, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException ignored) {
            }
        }
        return stub;
    }

    private static ProcessBuilder quickExitProcess() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return new ProcessBuilder("cmd", "/c", "exit", "0");
        }
        return new ProcessBuilder("true");
    }

    private static String slowGitExtension() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".cmd" : ".sh";
    }

    private static void writeHungProcessScript(Path path) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            Files.writeString(path, "@echo off\r\n:loop\r\ngoto loop\r\n");
        } else {
            Files.writeString(path, "#!/bin/sh\nwhile true; do : ; done\n");
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
            }
        }
    }

    private static void initRepo(Path repo) throws Exception {
        runGit(repo, "init");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test User");

        // Ensure HEAD exists so `git diff HEAD...HEAD` does not depend on an unborn branch.
        Files.writeString(repo.resolve("seed.txt"), "seed\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "seed.txt");
        runGit(repo, "commit", "-m", "seed");
    }

    private static String runGit(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int code = p.waitFor();
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String out = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            if (code != 0) {
                throw new RuntimeException("git failed (" + String.join(" ", command) + "):\n" + out);
            }
            return out;
        }
    }
}
