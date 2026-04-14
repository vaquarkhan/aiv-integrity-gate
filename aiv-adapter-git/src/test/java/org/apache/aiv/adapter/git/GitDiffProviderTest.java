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
import java.util.Random;
import java.util.List;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.nio.file.attribute.PosixFilePermissions;

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
        System.setProperty(TIMEOUT_PROP, "10");
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
        assertEquals("HEAD", diff.getBaseRef());
        assertEquals("HEAD", diff.getHeadRef());
        assertNotNull(diff.getChangedFiles());
        assertNotNull(diff.getRawDiff());
    }

    @Test
    void getDiffWithUnknownRefReturnsEmpty(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        var provider = new GitDiffProvider();
        Diff diff = provider.getDiff(repo.toAbsolutePath(), "nonexistent-ref-xyz", "HEAD");
        assertNotNull(diff);
        assertTrue(diff.getChangedFiles().isEmpty());
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
        assertEquals("HEAD", diff.getHeadRef());
        assertEquals("test@example.com", diff.getAuthorEmail());
        assertTrue(diff.getLinesAdded() >= 1);
        assertTrue(diff.getLinesDeleted() >= 1);

        List<String> paths = diff.getChangedFiles().stream().map(ChangedFile::getPath).toList();
        assertTrue(paths.contains("a-renamed.txt"));
        assertTrue(paths.contains("b.txt"));
        assertFalse(paths.contains("del.txt")); // deleted files are skipped
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
        assertTrue(diff.isSkipRequested());
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

        // This file name contains ".." and is rejected by sanitizePath.
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
        assertFalse(paths.contains("foo..bar.txt"));

        ChangedFile bigFile = diff.getChangedFiles().stream()
                .filter(f -> "big.txt".equals(f.getPath()))
                .findFirst()
                .orElseThrow();
        assertTrue(bigFile.getContent().isEmpty());
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
    }

    @Test
    void privateReadFileContentRejectsUnsanitizedOrEscapingPaths(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        var provider = new GitDiffProvider();
        Method m = GitDiffProvider.class.getDeclaredMethod("readFileContent", Path.class, String.class, String.class);
        m.setAccessible(true);

        // Backslashes normalize to forward slashes in sanitizePath, so equality check should reject this input.
        assertEquals("", (String) m.invoke(provider, repo.toAbsolutePath(), "a\\b.txt", "HEAD"));

        // Absolute paths should be rejected (would escape the workspace).
        assertEquals("", (String) m.invoke(provider, repo.toAbsolutePath(), "C:/Windows/System32/drivers/etc/hosts", "HEAD"));
    }

    @Test
    void privateGitHelpersGracefullyHandleMissingWorkspace() throws Exception {
        var provider = new GitDiffProvider();
        Path missing = Path.of("this-directory-should-not-exist-12345").toAbsolutePath().resolve("missing");

        Method numstat = GitDiffProvider.class.getDeclaredMethod("parseNumStat", Path.class, String.class, String.class);
        numstat.setAccessible(true);
        int[] loc = (int[]) numstat.invoke(provider, missing, "HEAD", "HEAD");
        assertEquals(0, loc[0]);
        assertEquals(0, loc[1]);

        Method author = GitDiffProvider.class.getDeclaredMethod("parseAuthor", Path.class, String.class, String.class);
        author.setAccessible(true);
        assertNull((String) author.invoke(provider, missing, "HEAD", "HEAD"));

        Method skip = GitDiffProvider.class.getDeclaredMethod("parseSkipRequested", Path.class, String.class, String.class);
        skip.setAccessible(true);
        assertFalse((boolean) skip.invoke(provider, missing, "HEAD", "HEAD"));

        Method raw = GitDiffProvider.class.getDeclaredMethod("runGitDiff", Path.class, String.class, String.class);
        raw.setAccessible(true);
        assertEquals("", (String) raw.invoke(provider, missing, "HEAD", "HEAD"));
    }

    @Test
    void privateHelpersCoverRemainingBranches(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        // sanitizePath: null/blank branches
        Method sanitize = GitDiffProvider.class.getDeclaredMethod("sanitizePath", String.class);
        sanitize.setAccessible(true);
        assertNull((String) sanitize.invoke(null, (Object) null));
        assertNull((String) sanitize.invoke(null, "   "));

        // parseChangedFiles: exception path when workspace directory doesn't exist
        Path missing = repo.resolve("does-not-exist");
        Method parseChangedFiles = GitDiffProvider.class.getDeclaredMethod(
                "parseChangedFiles", Path.class, String.class, String.class);
        parseChangedFiles.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ChangedFile> files = (List<ChangedFile>) parseChangedFiles.invoke(new GitDiffProvider(), missing, "HEAD", "HEAD");
        assertNotNull(files);
        assertTrue(files.isEmpty());

        // readFileContent: exception path when running `git show` from a missing workspace
        Method read = GitDiffProvider.class.getDeclaredMethod("readFileContent", Path.class, String.class, String.class);
        read.setAccessible(true);
        assertEquals("", (String) read.invoke(new GitDiffProvider(), missing, "x.txt", "HEAD"));

        // readFileContent: `git show` content length > 2MB should return empty string.
        // Use reflection to avoid calling getDiff(), which would also materialize a potentially huge raw diff.
        Files.writeString(repo.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "base.txt");
        runGit(repo, "commit", "-m", "base");

        String huge = "a".repeat(2 * 1024 * 1024 + 10);
        Files.writeString(repo.resolve("huge.txt"), huge, StandardCharsets.UTF_8);
        runGit(repo, "add", "huge.txt");
        runGit(repo, "commit", "-m", "add huge");
        Files.delete(repo.resolve("huge.txt"));

        assertEquals("", (String) read.invoke(new GitDiffProvider(), repo.toAbsolutePath(), "huge.txt", "HEAD"));
    }

    @Test
    void gitTimeoutSecondsReadsPropertyOrDefault() throws Exception {
        Method m = GitDiffProvider.class.getDeclaredMethod("gitTimeoutSeconds");
        m.setAccessible(true);
        String prop = "aiv.git.timeout.seconds";
        String prev = System.getProperty(prop);
        try {
            System.clearProperty(prop);
            assertEquals(120L, m.invoke(null));
            System.setProperty(prop, "  ");
            assertEquals(120L, m.invoke(null));
            System.setProperty(prop, "  3 ");
            assertEquals(3L, m.invoke(null));
            System.setProperty(prop, "-9");
            assertEquals(120L, m.invoke(null));
            System.setProperty(prop, "not-a-number");
            assertEquals(120L, m.invoke(null));
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
        Method m = GitDiffProvider.class.getDeclaredMethod("maxGitCaptureBytes");
        m.setAccessible(true);
        String prop = "aiv.git.capture.max.bytes";
        String prev = System.getProperty(prop);
        try {
            System.clearProperty(prop);
            assertEquals(64L * 1024 * 1024, m.invoke(null));
            System.setProperty(prop, "1024");
            assertEquals(1024L, m.invoke(null));
            System.setProperty(prop, "   ");
            assertEquals(64L * 1024 * 1024, m.invoke(null));
            System.setProperty(prop, "0");
            assertEquals(64L * 1024 * 1024, m.invoke(null));
            System.setProperty(prop, "bad");
            assertEquals(64L * 1024 * 1024, m.invoke(null));
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
        Method m = GitDiffProvider.class.getDeclaredMethod("gitProcessTimedOut", Process.class, long.class);
        m.setAccessible(true);
        ProcessBuilder pb = quickExitProcess();
        Process p = pb.start();
        assertFalse((boolean) m.invoke(null, p, 0L));
    }

    @Test
    void gitProcessTimedOutClampsNonPositiveTimeoutToOneSecond() throws Exception {
        Method m = GitDiffProvider.class.getDeclaredMethod("gitProcessTimedOut", Process.class, long.class);
        m.setAccessible(true);
        ProcessBuilder pb = quickExitProcess();
        Process p = pb.start();
        assertFalse((boolean) m.invoke(null, p, -5L));
    }

    @Test
    void gitProcessTimedOutDestroysHungProcess(@TempDir Path dir) throws Exception {
        Method m = GitDiffProvider.class.getDeclaredMethod("gitProcessTimedOut", Process.class, long.class);
        m.setAccessible(true);
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
            assertTrue((boolean) m.invoke(null, p, 1L));
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
    void runGitReadingFileReturnsEmptyStringWhenCaptureLimitExceeded(@TempDir Path repo) throws Exception {
        initRepo(repo);
        String cap = "aiv.git.capture.max.bytes";
        String prevCap = System.getProperty(cap);
        try {
            System.setProperty(cap, "2");
            Files.writeString(repo.resolve("z.txt"), "abcd\n", StandardCharsets.UTF_8);
            runGit(repo, "add", "z.txt");
            runGit(repo, "commit", "-m", "z");
            String base = runGit(repo, "rev-parse", "HEAD~1").trim();
            Diff diff = new GitDiffProvider().getDiff(repo.toAbsolutePath(), base, "HEAD");
            assertNotNull(diff);
            assertTrue(diff.getRawDiff().isEmpty());
        } finally {
            if (prevCap == null) {
                System.clearProperty(cap);
            } else {
                System.setProperty(cap, prevCap);
            }
        }
    }

    @Test
    void parseNumIgnoresNonNumericTokens() throws Exception {
        Method parseNum = GitDiffProvider.class.getDeclaredMethod("parseNum", String.class);
        parseNum.setAccessible(true);
        var provider = new GitDiffProvider();
        assertEquals(0, parseNum.invoke(provider, "x"));
    }

    @Test
    void gitSubcommandsHitTimeoutBranchesWhenExecutableHangs(@TempDir Path dir) throws Exception {
        Path slowGit = dir.resolve(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "slow-git.cmd" : "slow-git.sh");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Files.writeString(slowGit, "@echo off\r\n:loop\r\ngoto loop\r\n");
        } else {
            Files.writeString(slowGit, "#!/bin/sh\nwhile true; do :; done\n");
            slowGit.toFile().setExecutable(true, true);
        }

        String gitProp = "aiv.git.executable";
        String toProp = "aiv.git.timeout.seconds";
        String prevGit = System.getProperty(gitProp);
        String prevTo = System.getProperty(toProp);
        try {
            System.setProperty(gitProp, slowGit.toAbsolutePath().toString());
            System.setProperty(toProp, "1");

            Path ws = dir.resolve("ws");
            Files.createDirectories(ws);
            var provider = new GitDiffProvider();

            Method parseNumStat = GitDiffProvider.class.getDeclaredMethod(
                    "parseNumStat", Path.class, String.class, String.class);
            parseNumStat.setAccessible(true);
            Method runCap = GitDiffProvider.class.getDeclaredMethod(
                    "runGitReadingFile", ProcessBuilder.class, long.class);
            runCap.setAccessible(true);
            Method cfg = GitDiffProvider.class.getDeclaredMethod("configureGitChild", ProcessBuilder.class);
            cfg.setAccessible(true);
            ProcessBuilder sample = new ProcessBuilder(slowGit.toAbsolutePath().toString(), "diff", "HEAD...HEAD");
            sample.directory(ws.toFile());
            cfg.invoke(null, sample);
            assertNull(runCap.invoke(null, sample, 1L));

            int[] loc = (int[]) parseNumStat.invoke(provider, ws, "HEAD", "HEAD");
            assertEquals(0, loc[0]);
            assertEquals(0, loc[1]);

            Method parseAuthor = GitDiffProvider.class.getDeclaredMethod(
                    "parseAuthor", Path.class, String.class, String.class);
            parseAuthor.setAccessible(true);
            assertNull(parseAuthor.invoke(provider, ws, "HEAD", "HEAD"));

            Method parseSkip = GitDiffProvider.class.getDeclaredMethod(
                    "parseSkipRequested", Path.class, String.class, String.class);
            parseSkip.setAccessible(true);
            assertFalse((boolean) parseSkip.invoke(provider, ws, "HEAD", "HEAD"));

            Method runGitDiff = GitDiffProvider.class.getDeclaredMethod(
                    "runGitDiff", Path.class, String.class, String.class);
            runGitDiff.setAccessible(true);
            assertEquals("", runGitDiff.invoke(provider, ws, "HEAD", "HEAD"));

            Method parseChangedFiles = GitDiffProvider.class.getDeclaredMethod(
                    "parseChangedFiles", Path.class, String.class, String.class);
            parseChangedFiles.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ChangedFile> files = (List<ChangedFile>) parseChangedFiles.invoke(provider, ws, "HEAD", "HEAD");
            assertTrue(files.isEmpty());

            Path repo = dir.resolve("repo");
            Files.createDirectories(repo);
            initRepo(repo);
            Files.writeString(repo.resolve("timeout-show.txt"), "in-git\n", StandardCharsets.UTF_8);
            runGit(repo, "add", "timeout-show.txt");
            runGit(repo, "commit", "-m", "ts");
            Files.delete(repo.resolve("timeout-show.txt"));

            Method readFileContent = GitDiffProvider.class.getDeclaredMethod(
                    "readFileContent", Path.class, String.class, String.class);
            readFileContent.setAccessible(true);
            assertEquals("", readFileContent.invoke(
                    provider, repo.toAbsolutePath(), "timeout-show.txt", "HEAD"));
        } finally {
            if (prevGit == null) {
                System.clearProperty(gitProp);
            } else {
                System.setProperty(gitProp, prevGit);
            }
            if (prevTo == null) {
                System.clearProperty(toProp);
            } else {
                System.setProperty(toProp, prevTo);
            }
        }
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
