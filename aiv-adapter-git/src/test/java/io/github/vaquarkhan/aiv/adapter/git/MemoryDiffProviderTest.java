/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.git;

import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryDiffProviderTest {

    @Test
    void returnsInjectedDiff() {
        var diff = new Diff("a", "b", List.of(
                new ChangedFile("x.js", ChangedFile.ChangeType.ADDED, "const x = 1;")), "");
        var p = new MemoryDiffProvider(diff);
        assertEquals(diff, p.getDiff(Path.of("."), "ignored", "ignored"));
    }

    @Test
    void parseJsonRoundTrip(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "baseRef": "memory",
                  "headRef": "proposed",
                  "rawDiff": "diff --git a/a.js b/a.js\\n",
                  "files": [
                    { "path": "a.js", "changeType": "MODIFIED", "content": "const x = 1;\\nconst y = 2;" }
                  ]
                }
                """;
        Path f = dir.resolve("d.json");
        Files.writeString(f, json, StandardCharsets.UTF_8);
        Diff d = MemoryDiffProvider.fromJsonFile(f).getDiff(dir, "x", "y");
        assertEquals("memory", d.getBaseRef());
        assertEquals("proposed", d.getHeadRef());
        assertEquals(1, d.getChangedFiles().size());
        assertEquals("a.js", d.getChangedFiles().get(0).getPath());
        assertTrue(d.getChangedFiles().get(0).getContent().contains("const y"));
        assertTrue(d.getRawDiff().contains("diff --git"));
    }

    @Test
    void parseEmptyAndBadChangeType() {
        Diff empty = MemoryDiffProvider.parseJson("");
        assertTrue(empty.getChangedFiles().isEmpty());
        Diff d = MemoryDiffProvider.parseJson(
                "{\"files\":[{\"path\":\"t.py\",\"changeType\":\"nope\",\"content\":\"x\"}]}");
        assertEquals(ChangedFile.ChangeType.MODIFIED, d.getChangedFiles().get(0).getChangeType());
    }

    @Test
    void unescapeHandlesCommonEscapes() {
        assertEquals("a\nb\t\"\\/", MemoryDiffProvider.unescape("a\\nb\\t\\\"\\\\\\/"));
        assertEquals("\r", MemoryDiffProvider.unescape("\\r"));
        assertEquals("", MemoryDiffProvider.unescape(null));
        assertEquals("z", MemoryDiffProvider.unescape("\\z"));
    }

    @Test
    void skipsMalformedPathEntries() {
        Diff d = MemoryDiffProvider.parseJson("{\"files\":[{\"path\": 1, \"content\":\"x\"}, {\"path\":\"ok.py\",\"content\":\"y\"}]}");
        assertEquals(1, d.getChangedFiles().size());
        assertEquals("ok.py", d.getChangedFiles().get(0).getPath());
    }

    @Test
    void nullDiffConstructor() {
        assertTrue(new MemoryDiffProvider(null).getDiff(Path.of("."), "a", "b").getChangedFiles().isEmpty());
    }
}
