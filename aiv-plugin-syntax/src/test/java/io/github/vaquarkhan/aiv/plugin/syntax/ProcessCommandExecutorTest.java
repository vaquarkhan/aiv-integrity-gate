/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandExecutorTest {

    private static String javaExecutable() {
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    @Test
    void runsRealProcessAndReportsAvailable() throws Exception {
        var executor = new ProcessCommandExecutor();
        // "java -version" is guaranteed present during the build and writes to stderr, exit 0.
        CommandExecutor.ExecResult r = executor.run(List.of(javaExecutable(), "-version"), "");
        assertTrue(r.available());
        assertEquals(0, r.exitCode());
    }

    @Test
    void missingExecutableReportsUnavailable() throws Exception {
        var executor = new ProcessCommandExecutor();
        CommandExecutor.ExecResult r = executor.run(List.of("aiv_no_such_executable_zzz"), "some stdin");
        assertFalse(r.available());
    }
}
