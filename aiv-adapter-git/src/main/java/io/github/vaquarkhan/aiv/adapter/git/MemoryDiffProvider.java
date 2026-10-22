/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.git;

import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory / agent-facing {@link DiffProvider}. Loads a proposed change set from strict RFC 8259
 * JSON so gates can run without git (MCP tools, AI-DLC sensors, pre-commit agent loops, tests).
 *
 * <p>Minimal JSON shape:
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

    private final Diff diff;

    public MemoryDiffProvider(Diff diff) {
        this.diff = diff == null ? new Diff("memory", "proposed", List.of(), "") : diff;
    }

    public static MemoryDiffProvider fromJsonFile(Path jsonPath) throws Exception {
        String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
        return new MemoryDiffProvider(parseJson(json));
    }

    /** Parse and validate the agent-facing diff schema. Malformed entries fail loudly. */
    public static Diff parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new Diff("memory", "proposed", List.of(), "");
        }
        Object parsed = JsonLite.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("diff JSON root must be an object");
        }
        String base = stringOr(root.get("baseRef"), "memory", "baseRef");
        String head = stringOr(root.get("headRef"), "proposed", "headRef");
        String raw = stringOr(root.get("rawDiff"), "", "rawDiff");
        Object fileValue = root.get("files");
        if (fileValue != null && !(fileValue instanceof List<?>)) {
            throw new IllegalArgumentException("diff JSON 'files' must be an array");
        }

        List<ChangedFile> files = new ArrayList<>();
        for (Object entry : JsonLite.list(fileValue)) {
            if (!(entry instanceof Map<?, ?> file)) {
                throw new IllegalArgumentException("each diff JSON file entry must be an object");
            }
            String path = requiredString(file.get("path"), "file.path");
            String type = stringOr(file.get("changeType"), "MODIFIED", "file.changeType");
            String content = stringOr(file.get("content"), "", "file.content");
            files.add(new ChangedFile(path, parseChangeType(type), content));
        }
        return new Diff(base, head, files, raw);
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("diff JSON '" + field + "' must be a non-blank string");
        }
        return s;
    }

    private static String stringOr(Object value, String fallback, String field) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("diff JSON '" + field + "' must be a string");
        }
        return s;
    }

    private static ChangedFile.ChangeType parseChangeType(String raw) {
        try {
            return ChangedFile.ChangeType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("diff JSON 'file.changeType' must be ADDED, MODIFIED, or DELETED", e);
        }
    }

    @Override
    public Diff getDiff(Path workspace, String baseRef, String headRef) {
        return diff;
    }
}
