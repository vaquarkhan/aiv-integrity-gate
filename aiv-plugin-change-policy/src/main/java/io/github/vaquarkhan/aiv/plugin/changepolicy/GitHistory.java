/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs read-only {@code git} queries for history facts. Any failure (no repository, shallow clone,
 * unknown ref, timeout) yields an empty result, which makes the fact unknown rather than failing the run.
 *
 * @author Vaquar Khan
 */
@FunctionalInterface
interface GitHistory {

    Optional<String> run(Path workspace, List<String> args);

    static GitHistory process(long timeoutMs) {
        return (workspace, args) -> git(workspace, args, timeoutMs);
    }

    static Optional<String> git(Path workspace, List<String> args, long timeoutMs) {
        Path out = null;
        try {
            out = Files.createTempFile("aiv-change-policy", ".out");
            List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=off"));
            cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).directory(workspace.toFile())
                    .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
                return Optional.empty();
            }
            return p.exitValue() == 0 ? Optional.of(Files.readString(out, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            if (out != null) {
                out.toFile().delete();
            }
        }
    }
}
