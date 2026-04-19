/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a versioned JSON report for CI and dashboard consumers.
 */
public final class JsonReportWriter {

    private JsonReportWriter() {
    }

    /** Bumped when gate report shape changes (e.g. structured findings). */
    public static final int SCHEMA_VERSION = 2;

    public static void write(AIVResult result, Path outputFile, String cliVersion) throws IOException {
        StringBuilder json = new StringBuilder(512);
        json.append("{\n");
        json.append("  \"schema_version\": ").append(SCHEMA_VERSION).append(",\n");
        json.append("  \"aiv_cli_version\": \"").append(escape(cliVersion)).append("\",\n");
        json.append("  \"passed\": ").append(result.isPassed()).append(",\n");
        json.append("  \"notices\": ");
        appendStringArray(json, result.getNotices());
        json.append(",\n");
        json.append("  \"gates\": ");
        var gates = result.getGateResults();
        if (gates.isEmpty()) {
            json.append("[]\n");
        } else {
            json.append("[\n");
            for (int i = 0; i < gates.size(); i++) {
                GateResult g = gates.get(i);
                if (i > 0) {
                    json.append(",\n");
                }
                json.append("    {\n");
                json.append("      \"id\": \"").append(escape(g.getGateId())).append("\",\n");
                json.append("      \"passed\": ").append(g.isPassed()).append(",\n");
                json.append("      \"blocks_ci\": ").append(g.blocksCi()).append(",\n");
                json.append("      \"message\": \"").append(escape(g.getMessage())).append("\",\n");
                json.append("      \"findings\": ");
                appendFindings(json, g.getFindings());
                json.append("\n    }");
            }
            json.append("\n  ]\n");
        }
        json.append("}\n");
        Files.writeString(outputFile, json.toString(), StandardCharsets.UTF_8);
    }

    static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    private static void appendFindings(StringBuilder json, java.util.List<Finding> findings) {
        if (findings.isEmpty()) {
            json.append("[]");
            return;
        }
        json.append("[\n");
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) {
                json.append(",\n");
            }
            Finding f = findings.get(i);
            json.append("        {\n");
            json.append("          \"rule_id\": \"").append(escape(f.getRuleId())).append("\",\n");
            json.append("          \"file\": \"").append(escape(f.getFilePath())).append("\",\n");
            json.append("          \"start_line\": ").append(f.getStartLine());
            if (f.getStartColumn() != null) {
                json.append(",\n          \"start_column\": ").append(f.getStartColumn());
            }
            if (f.getEndLine() != null) {
                json.append(",\n          \"end_line\": ").append(f.getEndLine());
            }
            if (f.getEndColumn() != null) {
                json.append(",\n          \"end_column\": ").append(f.getEndColumn());
            }
            json.append(",\n          \"message\": \"").append(escape(f.getMessage())).append("\"\n");
            json.append("        }");
        }
        json.append("\n      ]");
    }

    private static void appendStringArray(StringBuilder json, java.util.List<String> items) {
        if (items.isEmpty()) {
            json.append("[]");
            return;
        }
        json.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                json.append(",\n");
            }
            json.append("    \"").append(escape(items.get(i))).append("\"");
        }
        json.append("\n  ]");
    }
}
