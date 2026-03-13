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

package org.apache.aiv.cli;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void invalidGitRefIsHandledAndReturnsOne(@TempDir Path repo) throws Exception {
        initRepo(repo);
        int code = Main.run(new String[]{
                "--workspace", repo.toString(),
                "--diff", "; rm -rf /",
                "--head", "HEAD"
        });
        assertEquals(1, code);
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
        assertTrue(code == 0 || code == 1);
    }

    @Test
    void mainDelegatesToRunAndUsesExitHandler() {
        int[] code = new int[]{-1};
        var previous = Main.EXIT;
        try {
            Main.EXIT = c -> code[0] = c;
            Main.main(new String[]{"--diff", "; rm -rf /"});
            assertEquals(1, code[0]);
        } finally {
            Main.EXIT = previous;
        }
    }

    @Test
    void canInstantiateMainForCoverage() {
        assertNotNull(new Main());
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
