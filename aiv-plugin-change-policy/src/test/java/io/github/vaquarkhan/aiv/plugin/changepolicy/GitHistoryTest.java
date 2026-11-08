/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHistoryTest {

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com",
                "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }

    @Test
    void readsHistoryFromARealRepository(@TempDir Path dir) throws Exception {
        git(dir, "init", "-q");
        Files.writeString(dir.resolve("a.txt"), "a");
        git(dir, "add", "a.txt");
        git(dir, "commit", "-q", "-m", "first");
        Files.writeString(dir.resolve("a.txt"), "b");
        git(dir, "commit", "-q", "-am", "feat: second", "-m", "with body");
        GitHistory h = GitHistory.process(10_000);
        assertEquals(Optional.of("2"), h.run(dir, List.of("rev-list", "--count", "HEAD")).map(String::strip));
        assertEquals(Optional.empty(), h.run(dir, List.of("rev-list", "--count", "nope..HEAD")), "git error is empty");
    }

    @Test
    void failuresAreEmpty(@TempDir Path dir) {
        assertTrue(GitHistory.git(dir.resolve("missing"), List.of("status"), 5_000).isEmpty(), "cannot start");
        // Not in the temp dir: on Windows the git wrapper's child can hold the directory briefly after the kill.
        assertTrue(GitHistory.git(Path.of("."), List.of("hash-object", "--stdin"), 300).isEmpty(),
                "timeout while waiting on stdin");
    }
}
