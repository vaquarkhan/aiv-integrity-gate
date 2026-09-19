/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import java.util.List;

/**
 * Runs an external command feeding {@code stdin}, capturing exit code and stderr. Abstracted so the
 * process-spawning logic can be replaced by a fake in tests.
 */
public interface CommandExecutor {

    ExecResult run(List<String> command, String stdin) throws Exception;

    /** {@code available=false} indicates the executable was not found (check is skipped, not failed). */
    record ExecResult(int exitCode, String stderr, boolean available) {
    }
}
