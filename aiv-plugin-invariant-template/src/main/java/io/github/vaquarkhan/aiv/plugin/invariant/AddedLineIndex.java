/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.invariant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts added (+) lines per path from a unified diff ({@link io.github.vaquarkhan.aiv.model.Diff#getRawDiff()}).
 */
final class AddedLineIndex {

    private final Map<String, Set<String>> addedByPath;

    private AddedLineIndex(Map<String, Set<String>> addedByPath) {
        this.addedByPath = addedByPath;
    }

    static AddedLineIndex fromRawDiff(String rawDiff) {
        Map<String, Set<String>> map = new HashMap<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return new AddedLineIndex(map);
        }
        String current = null;
        for (String line : rawDiff.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                String rest = line.substring(4).trim();
                if (rest.startsWith("b/")) {
                    rest = rest.substring(2);
                }
                if ("/dev/null".equals(rest)) {
                    current = null;
                } else {
                    current = rest.replace('\\', '/');
                    map.computeIfAbsent(current, k -> new HashSet<>());
                }
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                map.get(current).add(line.substring(1));
            }
        }
        return new AddedLineIndex(map);
    }

    boolean hasData() {
        return !addedByPath.isEmpty();
    }

    /**
     * True when any line of {@code content} that matches {@code needle} (case-insensitive substring)
     * also appears as an added line for {@code path}, or when there is no diff index (fall back: whole file).
     */
    boolean markerOnAddedLine(String path, String content, java.util.regex.Pattern needle) {
        if (content == null || content.isBlank()) {
            return false;
        }
        if (!hasData()) {
            return needle.matcher(content).find();
        }
        String norm = path == null ? "" : path.replace('\\', '/');
        Set<String> added = addedByPath.get(norm);
        if (added == null || added.isEmpty()) {
            // File in changed set but not in rawDiff — treat full content (e.g. ADDED without patch).
            return needle.matcher(content).find();
        }
        for (String addedLine : added) {
            if (needle.matcher(addedLine).find()) {
                return true;
            }
        }
        return false;
    }

    List<String> paths() {
        return new ArrayList<>(addedByPath.keySet());
    }
}
