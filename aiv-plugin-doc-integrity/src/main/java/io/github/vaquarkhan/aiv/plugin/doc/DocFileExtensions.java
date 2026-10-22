/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

import java.util.Set;

/**
 * File extensions and names for documentation files.
 *
 * @author Vaquar Khan
 */
public final class DocFileExtensions {

    private static final Set<String> EXTENSIONS = Set.of(".md", ".txt", ".rst");
    private static final Set<String> NAMES = Set.of("AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md", "README.md");

    private DocFileExtensions() {
    }

    public static boolean matches(String path) {
        if (path == null || path.isEmpty()) return false;
        String normalized = path.replace("\\", "/");
        String base = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf("/") + 1) : normalized;
        if (NAMES.contains(base)) return true;
        String lower = path.toLowerCase();
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
