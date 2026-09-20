/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.git;

import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.port.DiffProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory / agent-facing {@link DiffProvider}. Loads a proposed change set from JSON so gates can
 * run without git (MCP tools, pre-commit agent loops, unit tests).
 *
 * <p>Minimal JSON shape (SnakeYAML-friendly; no external JSON lib required in this module):
 * <pre>
 * {
 *   "baseRef": "memory",
 *   "headRef": "proposed",
 *   "rawDiff": "",
 *   "files": [
 *     { "path": "src/A.java", "changeType": "MODIFIED", "content": "class A {}" }
 *   ]
 * }
 * </pre>
 */
public final class MemoryDiffProvider implements DiffProvider {

    private static final Pattern PATH = Pattern.compile("\"path\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern CHANGE = Pattern.compile("\"changeType\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);
    private static final Pattern BASE = Pattern.compile("\"baseRef\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern HEAD = Pattern.compile("\"headRef\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern RAW = Pattern.compile("\"rawDiff\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);

    private final Diff diff;

    public MemoryDiffProvider(Diff diff) {
        this.diff = diff == null ? new Diff("memory", "proposed", List.of(), "") : diff;
    }

    public static MemoryDiffProvider fromJsonFile(Path jsonPath) throws Exception {
        String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
        return new MemoryDiffProvider(parseJson(json));
    }

    public static Diff parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new Diff("memory", "proposed", List.of(), "");
        }
        String base = first(BASE, json, "memory");
        String head = first(HEAD, json, "proposed");
        String raw = unescape(first(RAW, json, ""));
        List<ChangedFile> files = new ArrayList<>();
        // Split roughly on file objects
        int idx = 0;
        while (true) {
            int p = json.indexOf("\"path\"", idx);
            if (p < 0) {
                break;
            }
            int end = json.indexOf("\"path\"", p + 6);
            String chunk = end < 0 ? json.substring(p) : json.substring(p, end);
            Matcher pm = PATH.matcher(chunk);
            if (!pm.find()) {
                idx = p + 6;
                continue;
            }
            String path = unescape(pm.group(1));
            Matcher cm = CHANGE.matcher(chunk);
            String ct = cm.find() ? cm.group(1) : "MODIFIED";
            Matcher cont = CONTENT.matcher(chunk);
            String content = cont.find() ? unescape(cont.group(1)) : "";
            files.add(new ChangedFile(path, parseChangeType(ct), content));
            idx = p + 6;
        }
        return new Diff(unescape(base), unescape(head), files, raw);
    }

    private static ChangedFile.ChangeType parseChangeType(String raw) {
        try {
            return ChangedFile.ChangeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return ChangedFile.ChangeType.MODIFIED;
        }
    }

    private static String first(Pattern p, String json, String def) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : def;
    }

    static String unescape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(switch (n) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    default -> n;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public Diff getDiff(Path workspace, String baseRef, String headRef) {
        return diff;
    }
}
