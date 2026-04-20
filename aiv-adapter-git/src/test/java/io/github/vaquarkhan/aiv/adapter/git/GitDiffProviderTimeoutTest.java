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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class GitDiffProviderTimeoutTest {

    private static final String TIMEOUT_PROP = "aiv.git.timeout.seconds";
    private static final String GIT_EXECUTABLE_PROP = "aiv.git.executable";

    @Test
    void getDiffGracefullyHandlesGitTimeouts(@TempDir Path workspace) throws Exception {
        Path slowGit = workspace.resolve(slowGitFilename());
        writeInfiniteLoopScript(slowGit);

        String previousTimeout = System.getProperty(TIMEOUT_PROP);
        String previousGit = System.getProperty(GIT_EXECUTABLE_PROP);
        try {
            System.setProperty(TIMEOUT_PROP, "1");
            System.setProperty(GIT_EXECUTABLE_PROP, slowGit.toAbsolutePath().toString());

            var provider = new GitDiffProvider();
            assertThrows(IllegalStateException.class, () ->
                    provider.getDiff(workspace.toAbsolutePath(), "HEAD", "HEAD"));
        } finally {
            if (previousTimeout == null) {
                System.clearProperty(TIMEOUT_PROP);
            } else {
                System.setProperty(TIMEOUT_PROP, previousTimeout);
            }
            if (previousGit == null) {
                System.clearProperty(GIT_EXECUTABLE_PROP);
            } else {
                System.setProperty(GIT_EXECUTABLE_PROP, previousGit);
            }
        }
    }

    private static String slowGitFilename() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "slow-git.cmd"
                : "slow-git.sh";
    }

    private static void writeInfiniteLoopScript(Path path) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            Files.writeString(path,
                    "@echo off\r\n" + ":loop\r\n" + "goto loop\r\n");
        } else {
            Files.writeString(path, "#!/bin/sh\nwhile true; do :; done\n");
            path.toFile().setExecutable(true, true);
        }
    }
}
