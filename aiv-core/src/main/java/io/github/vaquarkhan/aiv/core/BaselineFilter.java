/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Suppresses findings listed in a baseline file so legacy repos can adopt AIV gradually.
 * <p>
 * Line format (UTF-8, {@code #} comments allowed):
 * <pre>
 * rule_id|file_path
 * rule_id|file_path|optional message substring
 * </pre>
 * Paths use {@code /}. Matching is case-insensitive on path. Message substring is optional.
 */
public final class BaselineFilter {

    private final List<Entry> entries;

    BaselineFilter(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static BaselineFilter load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Baseline file not found: " + file);
        }
        List<Entry> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", 3);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IOException("Invalid baseline line (need rule_id|file): " + raw);
            }
            String msg = parts.length >= 3 ? parts[2].trim() : "";
            out.add(new Entry(parts[0].trim(), norm(parts[1].trim()), msg));
        }
        return new BaselineFilter(out);
    }

    public static BaselineFilter empty() {
        return new BaselineFilter(List.of());
    }

    public GateResult apply(GateResult raw) {
        if (raw == null || raw.isPassed() || entries.isEmpty()) {
            return raw;
        }
        List<Finding> findings = raw.getFindings();
        if (findings.isEmpty()) {
            // No structured findings: suppress whole gate only if an entry matches gate id as rule.
            if (matchesGateMessage(raw.getGateId(), raw.getMessage())) {
                return GateResult.pass(raw.getGateId());
            }
            return raw;
        }
        List<Finding> kept = new ArrayList<>();
        for (Finding f : findings) {
            if (!isSuppressed(f)) {
                kept.add(f);
            }
        }
        if (kept.isEmpty()) {
            return GateResult.pass(raw.getGateId());
        }
        if (kept.size() == findings.size()) {
            return raw;
        }
        String msg = String.join("\n", kept.stream().map(Finding::getMessage).toList());
        if (raw.blocksCi()) {
            return GateResult.fail(raw.getGateId(), msg, kept);
        }
        return GateResult.advisory(raw.getGateId(), msg, kept);
    }

    boolean isSuppressed(Finding f) {
        String path = norm(f.getFilePath());
        for (Entry e : entries) {
            if (!e.ruleId.equals(f.getRuleId())) {
                continue;
            }
            if (!pathMatches(path, e.file)) {
                continue;
            }
            if (e.messageSub.isEmpty() || f.getMessage().toLowerCase(Locale.ROOT)
                    .contains(e.messageSub.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean pathMatches(String findingPath, String baselinePath) {
        if (findingPath.equals(baselinePath)) {
            return true;
        }
        return findingPath.endsWith("/" + baselinePath) || baselinePath.endsWith("/" + findingPath);
    }

    private boolean matchesGateMessage(String gateId, String message) {
        String msg = message == null ? "" : message;
        for (Entry e : entries) {
            if (e.ruleId.equals(gateId) || e.ruleId.startsWith(gateId + ".")) {
                if (e.messageSub.isEmpty() || msg.toLowerCase(Locale.ROOT)
                        .contains(e.messageSub.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    static String norm(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    int size() {
        return entries.size();
    }

    record Entry(String ruleId, String file, String messageSub) {}
}
