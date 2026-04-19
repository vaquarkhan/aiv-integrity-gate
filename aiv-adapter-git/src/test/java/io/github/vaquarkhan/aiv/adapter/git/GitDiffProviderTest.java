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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
    }

    @Test
    void privateReadFileContentRejectsUnsanitizedOrEscapingPaths(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        var provider = new GitDiffProvider();
        Method m = GitDiffProvider.class.getDeclaredMethod("readFileContent", Path.class, String.class, String.class, List.class);
        m.setAccessible(true);
        var w = new ArrayList<String>();

        // Backslashes normalize to forward slashes in sanitizePath, so equality check should reject this input.
        assertEquals("", (String) m.invoke(provider, repo.toAbsolutePath(), "a\\b.txt", "HEAD", w));

        // Absolute paths should be rejected (would escape the workspace).
        assertEquals("", (String) m.invoke(provider, repo.toAbsolutePath(), "C:/Windows/System32/drivers/etc/hosts", "HEAD", w));
    }

    @Test
    void privateGitHelpersGracefullyHandleMissingWorkspace() throws Exception {
        var provider = new GitDiffProvider();
        Path missing = Path.of("this-directory-should-not-exist-12345").toAbsolutePath().resolve("missing");

        Method author = GitDiffProvider.class.getDeclaredMethod("parseAuthor", Path.class, String.class);
        author.setAccessible(true);
        assertNull((String) author.invoke(provider, missing, "HEAD"));
    }

    @Test
    void privateHelpersCoverRemainingBranches(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);

        // sanitizePath: null/blank branches
        Method sanitize = GitDiffProvider.class.getDeclaredMethod("sanitizePath", String.class);
        sanitize.setAccessible(true);
        assertNull((String) sanitize.invoke(null, (Object) null));
        assertNull((String) sanitize.invoke(null, "   "));

        // readFileContent: exception path when running `git show` from a missing workspace
        Path missing = repo.resolve("does-not-exist");
        Method read = GitDiffProvider.class.getDeclaredMethod("readFileContent", Path.class, String.class, String.class, List.class);
        read.setAccessible(true);
        var warn = new ArrayList<String>();
        assertEquals("", (String) read.invoke(new GitDiffProvider(), missing, "x.txt", "HEAD", warn));

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

        warn.clear();
        assertEquals("", (String) read.invoke(new GitDiffProvider(), repo.toAbsolutePath(), "huge.txt", "HEAD", warn));
        assertTrue(warn.stream().anyMatch(s -> s.contains("huge.txt")));
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
    void getDiffFailsClosedWhenNameStatusFails(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("failNameStatus", true));
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
    void getDiffFailsClosedWhenNameStatusTimesOut(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        Path stub = writeDelegatingGitStub(repo, Map.of("hangOnNameStatus", true));
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
        Method m = GitDiffProvider.class.getDeclaredMethod("parseNumStatStrict", Path.class, String.class, String.class);
        m.setAccessible(true);
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", Path.of("no-such-git-" + System.nanoTime() + ".exe").toString());
            InvocationTargetException ite = assertThrows(InvocationTargetException.class, () ->
                    m.invoke(provider, Path.of(".").toAbsolutePath(), "HEAD", "HEAD"));
            assertInstanceOf(IllegalStateException.class, ite.getCause());
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
        Method m = GitDiffProvider.class.getDeclaredMethod("parseSkipDirectiveInLatestCommit", Path.class, String.class);
        m.setAccessible(true);
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", repo.resolve("missing-git-" + System.nanoTime()).toString());
            assertEquals(false, m.invoke(new GitDiffProvider(), repo.toAbsolutePath(), "HEAD"));
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
        Method m = GitDiffProvider.class.getDeclaredMethod("runGitDiffStrict", Path.class, String.class, String.class);
        m.setAccessible(true);
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", Path.of("missing-git-" + System.nanoTime() + ".exe").toString());
            InvocationTargetException ite = assertThrows(InvocationTargetException.class, () ->
                    m.invoke(new GitDiffProvider(), Path.of(".").toAbsolutePath(), "HEAD", "HEAD"));
            assertInstanceOf(IllegalStateException.class, ite.getCause());
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
        Method m = GitDiffProvider.class.getDeclaredMethod("parseChangedFiles", Path.class, String.class, String.class, List.class);
        m.setAccessible(true);
        String prev = System.getProperty("aiv.git.executable");
        try {
            System.setProperty("aiv.git.executable", Path.of("missing-git-" + System.nanoTime() + ".exe").toString());
            InvocationTargetException ite = assertThrows(InvocationTargetException.class, () ->
                    m.invoke(new GitDiffProvider(), Path.of(".").toAbsolutePath(), "HEAD", "HEAD", new ArrayList<String>()));
            assertInstanceOf(IllegalStateException.class, ite.getCause());
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void readFileContentAddsWarningWhenGitShowTimesOut(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
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
            String base = runGit(repo, "rev-parse", "HEAD~1").trim();
            Files.delete(repo.resolve("only-in-git.txt"));
            Method read = GitDiffProvider.class.getDeclaredMethod("readFileContent", Path.class, String.class, String.class, List.class);
            read.setAccessible(true);
            var warn = new ArrayList<String>();
            assertEquals("", read.invoke(new GitDiffProvider(), repo.toAbsolutePath(), "only-in-git.txt", "HEAD", warn));
            assertTrue(warn.stream().anyMatch(s -> s.contains("timed out") && s.contains("only-in-git")));
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
    void readFileContentReturnsEmptyWhenGitShowFails(@TempDir(cleanup = CleanupMode.NEVER) Path repo) throws Exception {
        initRepo(repo);
        var provider = new GitDiffProvider();
        Method read = GitDiffProvider.class.getDeclaredMethod("readFileContent", Path.class, String.class, String.class, List.class);
        read.setAccessible(true);
        Files.writeString(repo.resolve("x.txt"), "x\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "x.txt");
        runGit(repo, "commit", "-m", "x");
        Files.delete(repo.resolve("x.txt"));
        var warn = new ArrayList<String>();
        assertEquals("", read.invoke(provider, repo.toAbsolutePath(), "x.txt", "not-a-valid-ref-zzz", warn));
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
        boolean failNameStatus = Boolean.TRUE.equals(modes.get("failNameStatus"));
        boolean hangOnNumstat = Boolean.TRUE.equals(modes.get("hangOnNumstat"));
        boolean hangOnNameStatus = Boolean.TRUE.equals(modes.get("hangOnNameStatus"));
        boolean failLogFormat = Boolean.TRUE.equals(modes.get("failLogFormat"));
        boolean failLogPretty = Boolean.TRUE.equals(modes.get("failLogPretty"));
        boolean hangOnShow = Boolean.TRUE.equals(modes.get("hangOnShow"));
        if (win) {
            StringBuilder sb = new StringBuilder();
            sb.append("@echo off\n");
            sb.append("set \"REAL_GIT=").append(realGit).append("\"\n");
            if (failNumstat) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--numstat\" exit /b 1\n");
            }
            if (failNameStatus) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--name-status\" exit /b 1\n");
            }
            if (hangOnNumstat) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--numstat\" (\n");
                sb.append("  :hangnum\n");
                sb.append("  goto hangnum\n");
                sb.append(")\n");
            }
            if (hangOnNameStatus) {
                sb.append("if /I \"%~1\"==\"diff\" if /I \"%~2\"==\"--name-status\" (\n");
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
            if (failNumstat) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --numstat ]; then exit 1; fi\n");
            }
            if (failNameStatus) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --name-status ]; then exit 1; fi\n");
            }
            if (hangOnNumstat) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --numstat ]; then while true; do :; done; fi\n");
            }
            if (hangOnNameStatus) {
                sb.append("if [ \"$1\" = diff ] && [ \"$2\" = --name-status ]; then while true; do :; done; fi\n");
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
