/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

import java.nio.file.Path;

/** Shared path resolution helpers for doc validators. */
final class DocPathUtils {

    DocPathUtils() {
    }

    /**
     * Resolves a repo-relative path from the workspace root (fallback when resolution from the
     * document's directory would leave the workspace).
     */
    static Path resolveFromWorkspaceRoot(Path workspace, String normalized) {
        return workspace.resolve(normalized).normalize();
    }
}
