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
import java.util.ArrayList;
import java.util.List;

/**
 * Writes SARIF 2.1.0 for GitHub Code Scanning and compatible viewers.
 */
public final class SarifReportWriter {

    private SarifReportWriter() {
    }

    public static void write(AIVResult result, Path outputFile, String cliVersion) throws IOException {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");
        sb.append("  \"$schema\": \"https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json\",\n");
        sb.append("  \"version\": \"2.1.0\",\n");
        sb.append("  \"runs\": [\n    {\n");
        sb.append("      \"tool\": {\n        \"driver\": {\n");
        sb.append("          \"name\": \"aiv-cli\",\n");
        sb.append("          \"version\": \"").append(JsonReportWriter.escape(cliVersion)).append("\",\n");
        sb.append("          \"informationUri\": \"https://github.com/vaquarkhan/aiv-integrity-gate\"\n");
        sb.append("        }\n      },\n");
        sb.append("      \"results\": ");
        appendResults(sb, result);
        sb.append("\n    }\n  ]\n}\n");
        Files.writeString(outputFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendResults(StringBuilder sb, AIVResult result) {
        List<ResultEntry> flat = new ArrayList<>();
        for (GateResult g : result.getGateResults()) {
            if (g.isPassed()) {
                continue;
            }
            if (g.getFindings().isEmpty()) {
                flat.add(new ResultEntry(g.getGateId() + ".gate", null, 1, g.getMessage()));
            } else {
                for (Finding f : g.getFindings()) {
                    flat.add(new ResultEntry(f.getRuleId(), f.getFilePath(), f.getStartLine(), f.getMessage()));
                }
            }
        }
        if (flat.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < flat.size(); i++) {
            if (i > 0) {
                sb.append(",\n");
            }
            ResultEntry e = flat.get(i);
            sb.append("        {\n");
            sb.append("          \"ruleId\": \"").append(JsonReportWriter.escape(e.ruleId)).append("\",\n");
            sb.append("          \"level\": \"error\",\n");
            sb.append("          \"message\": { \"text\": \"").append(JsonReportWriter.escape(e.message)).append("\" }");
            if (e.filePath != null) {
                sb.append(",\n          \"locations\": [\n            {\n");
                sb.append("              \"physicalLocation\": {\n");
                sb.append("                \"artifactLocation\": { \"uri\": \"")
                        .append(JsonReportWriter.escape(toUriPath(e.filePath))).append("\" },\n");
                sb.append("                \"region\": { \"startLine\": ").append(e.line);
                sb.append(" }\n              }\n            }\n          ]");
            }
            sb.append("\n        }");
        }
        sb.append("\n      ]");
    }

    /** Use forward slashes for SARIF URI relative paths. */
    private static String toUriPath(String path) {
        return path.replace('\\', '/');
    }

    private record ResultEntry(String ruleId, String filePath, int line, String message) {
    }
}
