/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.external;

import io.github.vaquarkhan.aiv.util.JsonLite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the subset of SARIF 2.1.0 that every mainstream analyzer emits: {@code runs[].results[]} with
 * {@code ruleId}, {@code level}, {@code message.text} and the first physical location.
 */
final class SarifReader {

    /** One SARIF result, flattened. {@code level} is lower-case: error, warning, note or none. */
    record Result(String ruleId, String level, String message, String uri, int startLine) {
    }

    private SarifReader() {
    }

    static List<Result> read(String sarifJson) {
        Object root = JsonLite.parse(sarifJson);
        List<Result> out = new ArrayList<>();
        for (Object run : JsonLite.list(JsonLite.get(root, "runs"))) {
            for (Object r : JsonLite.list(JsonLite.get(run, "results"))) {
                String ruleId = JsonLite.str(JsonLite.get(r, "ruleId"));
                if (ruleId == null) {
                    ruleId = "unknown";
                }
                String level = JsonLite.str(JsonLite.get(r, "level"));
                level = level == null ? "warning" : level.toLowerCase(Locale.ROOT);
                String message = JsonLite.str(JsonLite.get(r, "message", "text"));
                String uri = null;
                int line = 1;
                List<Object> locations = JsonLite.list(JsonLite.get(r, "locations"));
                if (!locations.isEmpty()) {
                    Object phys = JsonLite.get(locations.get(0), "physicalLocation");
                    uri = JsonLite.str(JsonLite.get(phys, "artifactLocation", "uri"));
                    Object sl = JsonLite.get(phys, "region", "startLine");
                    if (sl instanceof Number n && n.intValue() >= 1) {
                        line = n.intValue();
                    }
                }
                out.add(new Result(ruleId, level, message == null ? "" : message, normalizeUri(uri), line));
            }
        }
        return out;
    }

    /** Strips {@code file://} and a leading {@code ./}, converts backslashes. Returns null for no location. */
    static String normalizeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String u = uri.trim().replace('\\', '/');
        if (u.startsWith("file://")) {
            u = u.substring("file://".length());
        }
        if (u.startsWith("./")) {
            u = u.substring(2);
        }
        return u;
    }

    /** Rank for comparing SARIF levels: none &lt; note &lt; warning &lt; error. */
    static int rank(String level) {
        switch (level) {
            case "error":
                return 3;
            case "warning":
                return 2;
            case "note":
                return 1;
            default:
                return 0;
        }
    }
}
