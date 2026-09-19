/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Default {@link CommandExecutor} backed by {@link ProcessBuilder}. A missing executable is reported
 * as {@code available=false} rather than thrown, so the caller can skip the check cleanly.
 */
public final class ProcessCommandExecutor implements CommandExecutor {

    @Override
    public ExecResult run(List<String> command, String stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException notFound) {
            return new ExecResult(-1, notFound.getMessage(), false);
        }
        try (OutputStream os = process.getOutputStream()) {
            os.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ExecResult(exitCode, stderr, true);
    }
}
