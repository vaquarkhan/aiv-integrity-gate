/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded subprocess executor for local syntax toolchains. */
public final class ProcessCommandExecutor implements CommandExecutor {

    static final long DEFAULT_TIMEOUT_SECONDS = 30;
    static final int MAX_DIAGNOSTIC_BYTES = 1024 * 1024;
    static boolean FORCE_DELETE_FAILURE = false;

    @Override
    public ExecResult run(List<String> command, String stdin) throws Exception {
        Path stderrFile = Files.createTempFile("aiv-syntax-stderr-", ".log");
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(stderrFile.toFile());
            try {
                process = pb.start();
            } catch (IOException notFound) {
                return new ExecResult(-1, notFound.getMessage(), false);
            }
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            long timeout = Long.getLong("aiv.syntax.timeout.seconds", DEFAULT_TIMEOUT_SECONDS);
            if (!process.waitFor(Math.max(1, timeout), TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new ExecResult(-2, "syntax tool timed out after " + Math.max(1, timeout) + "s", false);
            }
            byte[] bytes = Files.readAllBytes(stderrFile);
            int length = Math.min(bytes.length, MAX_DIAGNOSTIC_BYTES);
            String stderr = new String(bytes, 0, length, StandardCharsets.UTF_8);
            if (bytes.length > length) stderr += "\n[diagnostic truncated]";
            return new ExecResult(process.exitValue(), stderr, true);
        } finally {
            deleteQuietly(stderrFile);
        }
    }

    static void deleteQuietly(Path file) {
        try {
            if (FORCE_DELETE_FAILURE) throw new IOException("forced");
            Files.deleteIfExists(file);
        } catch (IOException locked) {
            file.toFile().deleteOnExit();
        }
    }
}
