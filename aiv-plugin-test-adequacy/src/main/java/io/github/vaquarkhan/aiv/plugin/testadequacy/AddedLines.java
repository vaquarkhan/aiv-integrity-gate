/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.testadequacy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Index of added ({@code +}) line texts per path, parsed from a unified diff.
 * Used to scope checks to what the change introduced, never to pre-existing lines.
 */
final class AddedLines {

    private final Map<String, Set<String>> byPath;

    private AddedLines(Map<String, Set<String>> byPath) {
        this.byPath = byPath;
    }

    static AddedLines parse(String rawDiff) {
        Map<String, Set<String>> map = new HashMap<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return new AddedLines(map);
        }
        String current = null;
        for (String line : rawDiff.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                String rest = line.substring(4).trim();
                if (rest.startsWith("b/")) {
                    rest = rest.substring(2);
                }
                current = "/dev/null".equals(rest) ? null : rest.replace('\\', '/');
                if (current != null) {
                    map.computeIfAbsent(current, k -> new HashSet<>());
                }
                continue;
            }
            if (current != null && line.startsWith("+") && !line.startsWith("+++")) {
                map.get(current).add(line.substring(1).trim());
            }
        }
        return new AddedLines(map);
    }

    boolean hasData() {
        return !byPath.isEmpty();
    }

    /**
     * True when {@code lineText} was added for {@code path}. When the diff carries no hunks at all
     * (in-memory diffs), every line of an ADDED file counts as added and MODIFIED files are not scoped.
     */
    boolean isAdded(String path, String lineText, boolean fileIsAdded) {
        if (!hasData()) {
            return fileIsAdded;
        }
        Set<String> lines = byPath.get(path == null ? "" : path.replace('\\', '/'));
        if (lines == null) {
            return fileIsAdded;
        }
        return lines.contains(lineText == null ? "" : lineText.trim());
    }
}
