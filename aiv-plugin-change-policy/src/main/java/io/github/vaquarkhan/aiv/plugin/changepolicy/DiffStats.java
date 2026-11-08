/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a unified {@code git diff} into added and removed lines per file. Only lines inside hunks
 * count, so {@code ---}/{@code +++} headers are never mistaken for content.
 *
 * @author Vaquar Khan
 */
final class DiffStats {

    record FileDiff(List<String> added, List<String> removed) {
        static final FileDiff EMPTY = new FileDiff(List.of(), List.of());

        int changed() {
            return added.size() + removed.size();
        }

        /** True when the file changed but every change disappears once whitespace is ignored. */
        boolean whitespaceOnly() {
            return changed() > 0 && squash(added).equals(squash(removed));
        }

        private static String squash(List<String> lines) {
            return String.join("", lines).replaceAll("\\s+", "");
        }
    }

    private DiffStats() {
    }

    static Map<String, FileDiff> parse(String raw) {
        Map<String, FileDiff> out = new HashMap<>();
        FileDiff current = null;
        boolean inHunk = false;
        for (String line : raw.split("\n", -1)) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (l.startsWith("diff --git ")) {
                int b = l.lastIndexOf(" b/");
                current = new FileDiff(new ArrayList<>(), new ArrayList<>());
                out.put(b < 0 ? l.substring(11) : l.substring(b + 3), current);
                inHunk = false;
            } else if (current != null && l.startsWith("@@")) {
                inHunk = true;
            } else if (inHunk && l.startsWith("+")) {
                current.added().add(l.substring(1));
            } else if (inHunk && l.startsWith("-")) {
                current.removed().add(l.substring(1));
            }
        }
        return out;
    }
}
