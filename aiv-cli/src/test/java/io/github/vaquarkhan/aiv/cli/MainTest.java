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

package io.github.vaquarkhan.aiv.cli;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aiv.adapter.github.GithubChecksPublisher;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.GateResult;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class MainTest {

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

    @AfterEach
    void restoreGithubEnv() {
        Main.GITHUB_ENV = System::getenv;
        System.clearProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY);
    }

    @Test
    void versionFlagExitsZeroAndPrintsResolvedVersion() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            assertEquals(0, Main.run(new String[]{"--version"}));
        } finally {
            System.setOut(prev);
        }
        assertEquals("aiv-cli " + Main.cliVersion(), buf.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void runWithDefaultArgs() {
        int code = Main.run(new String[0]);
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void runWithDiffArg() {
        int code = Main.run(new String[]{"--diff", "HEAD", "--head", "HEAD"});
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void runWithWorkspaceAndNoChangesReturnsZero(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD"
        });
        assertEquals(0, code);
    }

    @Test
    void runWithIncludeDocChecks(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--include-doc-checks"
        });
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void invalidGitRefIsHandledAndReturnsTwo(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "; rm -rf /",
                "--head", "HEAD"
        });
        assertEquals(2, code);
    }

    @Test
    void gitDiffFailureReturnsThree(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "nonexistent-ref-xyz",
                "--head", "HEAD"
        });
        assertEquals(3, code);
    }

    @Test
    void shortVersionFlagExitsZero() {
        assertEquals(0, Main.run(new String[]{"-V"}));
    }

    @Test
    void readVersionFromStreamNullReturnsDev() {
        assertEquals("dev", Main.readVersionFromStream(null));
    }

    @Test
    void readVersionFromStreamParsesVersion() {
        var in = new ByteArrayInputStream("version=2.3.4\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("2.3.4", Main.readVersionFromStream(in));
    }

    @Test
    void readVersionFromStreamBlankVersionReturnsDev() {
        var in = new ByteArrayInputStream("version=  \n".getBytes(StandardCharsets.UTF_8));
        assertEquals("dev", Main.readVersionFromStream(in));
    }

    @Test
    void readVersionFromStreamIOExceptionReturnsDev() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("no");
            }
        };
        assertEquals("dev", Main.readVersionFromStream(broken));
    }

    @Test
    void bareWorkspaceFlagDoesNotCrash(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{"--workspace"});
        assertTrue(code == 0 || code == 1 || code == 3);
    }

    @Test
    void bareDiffFlagDoesNotCrash(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{"--diff"});
        assertTrue(code == 0 || code == 1 || code == 3);
    }

    @Test
    void bareHeadFlagDoesNotCrash(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{"--head"});
        assertTrue(code == 0 || code == 1 || code == 3);
    }

    @Test
    void unknownFlagDoesNotCrash(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--not-a-real-flag"
        });
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void missingArgValuesDoNotCrash(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace",
                "--diff",
                "--head",
                "HEAD",
                "--workspace", repo.toString(),
                "--diff", "HEAD"
        });
        assertTrue(code == 0 || code == 1 || code == 3);
    }

    @Test
    void mainDelegatesToRunAndUsesExitHandler() {
        int[] code = new int[]{-1};
        var previous = Main.EXIT;
        try {
            Main.EXIT = c -> code[0] = c;
            Main.main(new String[]{"--diff", "; rm -rf /"});
            assertEquals(2, code[0]);
        } finally {
            Main.EXIT = previous;
        }
    }

    @Test
    void canInstantiateMainForCoverage() {
        assertNotNull(new Main());
    }

    @Test
    void explainGatePrintsHelp() {
        assertEquals(0, Main.run(new String[]{"explain", "density"}));
    }

    @Test
    void explainUnknownReturnsOne() {
        assertEquals(1, Main.run(new String[]{"explain", "does-not-exist-xyz"}));
    }

    @Test
    void initWritesConfig(@TempDir Path empty) {
        assertEquals(0, Main.run(new String[]{"init", "--workspace", empty.toString()}));
        assertTrue(Files.exists(empty.resolve(".aiv/config.yaml")));
    }

    @Test
    void doctorExitsZeroOnCleanRepo(@TempDir Path repo) throws Exception {
        initRepo(repo);
        assertEquals(0, Main.run(new String[]{
                "doctor", "--workspace", repo.toString(),
                "--diff", "HEAD", "--head", "HEAD"
        }));
    }

    @Test
    void explainWithoutGateIdReturnsOne() {
        assertEquals(1, Main.run(new String[]{"explain"}));
    }

    @Test
    void initWhenWorkspaceIsFileReturnsOne(@TempDir Path root) throws Exception {
        Path f = root.resolve("not-a-directory");
        Files.writeString(f, "x", StandardCharsets.UTF_8);
        assertEquals(1, Main.run(new String[]{"init", "--workspace", f.toString()}));
    }

    @Test
    void doctorFlagSetsInformationalMode(@TempDir Path repo) throws Exception {
        initRepo(repo);
        assertEquals(0, Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--doctor"
        }));
    }

    @Test
    void outputJsonWritesReport(@TempDir Path repo) throws Exception {
        initRepo(repo);
        Path json = repo.resolve("aiv-report.json");
        assertEquals(0, Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--output-json", json.toString()
        }));
        assertTrue(Files.exists(json));
        assertTrue(Files.readString(json).contains("\"schema_version\": 2"));
    }

    @Test
    void publishGithubChecksWithoutTokenReturnsTwo(@TempDir Path repo) throws Exception {
        initRepo(repo);
        assertEquals(2, Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--publish-github-checks"
        }));
    }

    @Test
    void publishGithubChecksInterruptReturnsTwo(@TempDir Path repo) throws Exception {
        CountDownLatch inHandler = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            inHandler.countDown();
            try {
                Thread.sleep(300_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            Main.GITHUB_ENV = Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "GITHUB_SHA", "abcde"
            )::get;
            initRepo(repo);
            AtomicInteger code = new AtomicInteger(-99);
            Thread worker = new Thread(() -> code.set(Main.run(new String[]{
                    "--workspace", repo.toString(),
                    "--diff", "HEAD",
                    "--head", "HEAD",
                    "--publish-github-checks"
            })));
            worker.start();
            assertTrue(inHandler.await(30, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(TimeUnit.MINUTES.toMillis(1));
            assertEquals(2, code.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishGithubChecksWithEnvPostsSuccessfully(@TempDir Path repo) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            Main.GITHUB_ENV = Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "GITHUB_SHA", "abcde"
            )::get;
            initRepo(repo);
            assertEquals(0, Main.run(new String[]{
                    "--workspace", repo.toString(),
                    "--diff", "HEAD",
                    "--head", "HEAD",
                    "--publish-github-checks"
            }));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void outputSarifWritesReport(@TempDir Path repo) throws Exception {
        initRepo(repo);
        Path sarif = repo.resolve("aiv.sarif");
        assertEquals(0, Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--output-sarif", sarif.toString()
        }));
        assertTrue(Files.exists(sarif));
        assertTrue(Files.readString(sarif).contains("\"version\": \"2.1.0\""));
    }

    @Test
    void invalidWarningsExitCodeReturnsTwo() {
        assertEquals(2, Main.run(new String[]{"--warnings-exit-code", "not-a-number"}));
    }

    @Test
    void mergeWarningsExitUsesConfiguredCodeWhenPassedWithNotices() {
        var last = new AIVResult(true, List.of(GateResult.pass("g")), List.of("warn"));
        assertEquals(4, Main.mergeWarningsExit(0, 4, last));
    }

    @Test
    void mergeWarningsExitKeepsCoreWhenNoNotices() {
        var last = new AIVResult(true, List.of(GateResult.pass("g")), List.of());
        assertEquals(0, Main.mergeWarningsExit(0, 4, last));
    }

    @Test
    void mergeWarningsExitKeepsFailureExit() {
        var last = new AIVResult(true, List.of(GateResult.pass("g")), List.of("w"));
        assertEquals(1, Main.mergeWarningsExit(1, 4, last));
    }

    @Test
    void mergeWarningsExitWhenLastNull() {
        assertEquals(0, Main.mergeWarningsExit(0, 4, null));
    }

    @Test
    void mergeWarningsExitWhenRunDidNotPass() {
        var last = new AIVResult(true, List.of(GateResult.pass("g")), List.of("w"));
        assertEquals(0, Main.mergeWarningsExit(0, 0, last));
    }

    @Test
    void mergeWarningsExitWhenPassedButNoNoticesDoesNotRemap() {
        var last = new AIVResult(true, List.of(GateResult.pass("g")), List.of());
        assertEquals(0, Main.mergeWarningsExit(0, 9, last));
    }

    @Test
    void mergeWarningsExitWhenFailedKeepsCoreExit() {
        var last = new AIVResult(false, List.of(GateResult.fail("g", "x")), List.of("w"));
        assertEquals(1, Main.mergeWarningsExit(1, 9, last));
    }

    @Test
    void readVersionFromStreamIoExceptionOnLoadReturnsDev() {
        InputStream in = new InputStream() {
            private int n;

            @Override
            public int read() throws IOException {
                if (n++ == 0) {
                    return 'x';
                }
                throw new IOException("x");
            }
        };
        assertEquals("dev", Main.readVersionFromStream(in));
    }

    @Test
    void outputJsonWhenTargetIsDirectoryReturnsTwo(@TempDir Path repo) throws Exception {
        initRepo(repo);
        Path dir = repo.resolve("isdir");
        Files.createDirectory(dir);
        assertEquals(2, Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--output-json", dir.toString()
        }));
    }

    @Test
    void warningsExitCodeParsesWithNormalRun(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "HEAD",
                "--head", "HEAD",
                "--warnings-exit-code", "9"
        });
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void parseWorkspaceWithBareFlagOnlyUsesDefault() throws Exception {
        Method parseWorkspace = Main.class.getDeclaredMethod("parseWorkspace", String[].class);
        parseWorkspace.setAccessible(true);
        Path w = (Path) parseWorkspace.invoke(null, (Object) new String[]{"--workspace"});
        assertNotNull(w);
    }

    @Test
    void tailReturnsEmptyWhenAtMostOneArg() throws Exception {
        Method tail = Main.class.getDeclaredMethod("tail", String[].class);
        tail.setAccessible(true);
        assertEquals(0, ((String[]) tail.invoke(null, (Object) new String[]{"doctor"})).length);
    }

    @Test
    void tailCopiesArgsAfterFirst() throws Exception {
        Method tail = Main.class.getDeclaredMethod("tail", String[].class);
        tail.setAccessible(true);
        String[] r = (String[]) tail.invoke(null, (Object) new String[]{"init", "--workspace", "x"});
        assertEquals(2, r.length);
        assertEquals("--workspace", r[0]);
        assertEquals("x", r[1]);
    }

    @Test
    void parseWorkspaceHonorsFlag(@TempDir Path dir) throws Exception {
        Method parseWorkspace = Main.class.getDeclaredMethod("parseWorkspace", String[].class);
        parseWorkspace.setAccessible(true);
        Path w = (Path) parseWorkspace.invoke(null, (Object) new String[]{"--workspace", dir.toString()});
        assertEquals(dir.toAbsolutePath().normalize(), w.normalize());
    }

    @Test
    void parseWorkspaceDefaultsToDot() throws Exception {
        Method parseWorkspace = Main.class.getDeclaredMethod("parseWorkspace", String[].class);
        parseWorkspace.setAccessible(true);
        Path w = (Path) parseWorkspace.invoke(null, (Object) new String[0]);
        assertNotNull(w);
    }

    private static void initRepo(Path repo) throws Exception {
        runGit(repo, "init");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test User");
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
                throw new RuntimeException("git failed:\n" + out);
            }
            return out;
        }
    }
}
