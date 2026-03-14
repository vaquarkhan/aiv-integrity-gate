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

import org.apache.aiv.model.Diff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitDiffProviderTimeoutTest {

    private static final String TIMEOUT_PROP = "aiv.git.timeout.seconds";

    @Test
    void getDiffGracefullyHandlesGitTimeouts(@TempDir Path workspace) throws Exception {
        // Place a fake git.cmd in the workspace so ProcessBuilder("git", ...) resolves to it.
        Files.writeString(workspace.resolve("git.cmd"),
                "@echo off\r\n" +
                        "set x=0\r\n" +
                        ":loop\r\n" +
                        "set /a x=x+1\r\n" +
                        "goto loop\r\n");

        String previous = System.getProperty(TIMEOUT_PROP);
        try {
            System.setProperty(TIMEOUT_PROP, "1");

            var provider = new GitDiffProvider();
            Diff diff = provider.getDiff(workspace.toAbsolutePath(), "HEAD", "HEAD");

            assertNotNull(diff);
            assertTrue(diff.getRawDiff().isEmpty());
            assertTrue(diff.getChangedFiles().isEmpty());
            assertEquals(0, diff.getLinesAdded());
            assertEquals(0, diff.getLinesDeleted());
            assertNull(diff.getAuthorEmail());
            assertFalse(diff.isSkipRequested());
        } finally {
            if (previous == null) {
                System.clearProperty(TIMEOUT_PROP);
            } else {
                System.setProperty(TIMEOUT_PROP, previous);
            }
        }
    }
}
