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
    void timesOutAndCapsDiagnostics() throws Exception {
        String previous = System.getProperty("aiv.syntax.timeout.seconds");
        try {
            System.setProperty("aiv.syntax.timeout.seconds", "1");
            var executor = new ProcessCommandExecutor();
            CommandExecutor.ExecResult timed = executor.run(List.of(javaExecutable(), "-cp",
                    System.getProperty("java.class.path"), Sleeper.class.getName()), "");
            assertFalse(timed.available());
            assertEquals(-2, timed.exitCode());
            System.setProperty("aiv.syntax.timeout.seconds", "30");
            CommandExecutor.ExecResult noisy = executor.run(List.of(javaExecutable(), "-cp",
                    System.getProperty("java.class.path"), Noisy.class.getName()), "");
            assertTrue(noisy.available());
            assertTrue(noisy.stderr().endsWith("[diagnostic truncated]"));
        } finally {
            if (previous == null) System.clearProperty("aiv.syntax.timeout.seconds");
            else System.setProperty("aiv.syntax.timeout.seconds", previous);
        }
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception { Thread.sleep(10_000); }
    }

    public static final class Noisy {
        public static void main(String[] args) { System.err.print("x".repeat(1024 * 1024 + 100)); }
    }

    @Test
    void cleanupFallbackIsCovered() throws Exception {
        Path file = java.nio.file.Files.createTempFile("aiv-delete-", ".tmp");
        try {
            ProcessCommandExecutor.FORCE_DELETE_FAILURE = true;
            ProcessCommandExecutor.deleteQuietly(file);
            assertTrue(java.nio.file.Files.exists(file));
        } finally {
            ProcessCommandExecutor.FORCE_DELETE_FAILURE = false;
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void missingExecutableReportsUnavailable() throws Exception {
        var executor = new ProcessCommandExecutor();
        CommandExecutor.ExecResult r = executor.run(List.of("aiv_no_such_executable_zzz"), "some stdin");
        assertFalse(r.available());
    }
}
