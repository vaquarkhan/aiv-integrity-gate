/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A temp file that cannot be deleted right away must not mask the git result (Windows handle race). */
class GitDiffProviderCleanupTest {

    @Test
    void deleteQuietlyRemovesFilesAndToleratesUndeletablePaths(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("a.out"), "x");
        GitDiffProvider.deleteQuietly(f);
        assertFalse(Files.exists(f));
        GitDiffProvider.deleteQuietly(f);
        Path busy = Files.createDirectory(dir.resolve("busy"));
        Files.writeString(busy.resolve("child"), "x");
        assertDoesNotThrow(() -> GitDiffProvider.deleteQuietly(busy), "a non-empty directory stands in for a locked file");
        assertTrue(Files.exists(busy));
    }
}
