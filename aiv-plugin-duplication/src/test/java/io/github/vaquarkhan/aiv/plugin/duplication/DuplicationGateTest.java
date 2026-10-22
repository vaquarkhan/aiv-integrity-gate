/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.duplication;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicationGateTest {

    private static final String BLOCK = """
            public int total(List<Item> items) {
                int sum = 0;
                for (Item it : items) {
                    sum += it.price() * it.qty();
                }
                log.info("total computed {}", sum);
                return sum * taxFactor;
            }
            """;

    private static AIVContext ctx(Path ws, Map<String, Object> cfg, String rawDiff, ChangedFile... files) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(DuplicationGate.ID, true, cfg)), Map.of());
        return new AIVContext(ws, new Diff("main", "HEAD", List.of(files), rawDiff), config);
    }

    @Test
    void registeredAndAdvisoryOnClone(@TempDir Path ws) throws IOException {
        assertTrue(ServiceLoader.load(QualityGate.class).stream().anyMatch(p -> p.type() == DuplicationGate.class));
        assertEquals("duplication", new DuplicationGate().getId());

        Files.createDirectories(ws.resolve("src/main/java/legacy"));
        Files.writeString(ws.resolve("src/main/java/legacy/Old.java"), "package legacy;\nclass Old {\n" + BLOCK + "}\n");
        Files.createDirectories(ws.resolve("target"));
        Files.writeString(ws.resolve("target/Copy.java"), BLOCK); // build output: ignored
        Files.writeString(ws.resolve("notes.txt"), BLOCK);        // not a code extension: ignored

        String newFile = "package fresh;\nclass New {\n" + BLOCK + "}\n";
        GateResult r = new DuplicationGate().evaluate(ctx(ws, Map.of(), "",
                new ChangedFile("src/main/java/fresh/New.java", ChangedFile.ChangeType.ADDED, newFile)));
        assertFalse(r.isPassed());
        assertFalse(r.blocksCi(), "duplication is advisory, never a block");
        assertEquals(1, r.getFindings().size());
        assertEquals(DuplicationGate.RULE, r.getFindings().get(0).getRuleId());
        assertEquals("src/main/java/fresh/New.java", r.getFindings().get(0).getFilePath());
        assertEquals(3, r.getFindings().get(0).getStartLine());
        assertTrue(r.getMessage().contains("src/main/java/legacy/Old.java:3"), r.getMessage());
    }

    @Test
    void noCloneWhenBlockIsUniqueOrTooShort(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("A.java"), "class A {\n  int a = 1 + 2;\n  int b = 2 + 3;\n}\n");
        GateResult unique = new DuplicationGate().evaluate(ctx(ws, Map.of(), "",
                new ChangedFile("B.java", ChangedFile.ChangeType.ADDED, "class B {\n" + BLOCK + "}\n")));
        assertTrue(unique.isPassed());
        // the same two significant lines exist in A.java but are below the window
        GateResult tooShort = new DuplicationGate().evaluate(ctx(ws, Map.of(), "",
                new ChangedFile("C.java", ChangedFile.ChangeType.ADDED, "class C {\n  int a = 1 + 2;\n  int b = 2 + 3;\n}\n")));
        assertTrue(tooShort.isPassed());
        GateResult smallWindow = new DuplicationGate().evaluate(ctx(ws, Map.of("min_lines", 2), "",
                new ChangedFile("C.java", ChangedFile.ChangeType.ADDED, "class C {\n  int a = 1 + 2;\n  int b = 2 + 3;\n}\n")));
        assertFalse(smallWindow.isPassed());
    }

    @Test
    void onlyAddedLinesOfModifiedFilesAreProbed(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("Old.java"), "class Old {\n" + BLOCK + "}\n");
        String modified = "class M {\n" + BLOCK + "  int fresh = 1;\n}\n";
        // Diff says only one trivial line was added: the pre-existing clone must not be reported.
        String rawDiff = "--- a/M.java\n+++ b/M.java\n@@ -9,0 +10 @@\n+  int fresh = 1;\n";
        GateResult r = new DuplicationGate().evaluate(ctx(ws, Map.of(), rawDiff,
                new ChangedFile("M.java", ChangedFile.ChangeType.MODIFIED, modified)));
        assertTrue(r.isPassed());
        // Without a diff index a MODIFIED file is not probed at all (precision-first).
        GateResult noIndex = new DuplicationGate().evaluate(ctx(ws, Map.of(), "",
                new ChangedFile("M.java", ChangedFile.ChangeType.MODIFIED, modified)));
        assertTrue(noIndex.isPassed());
        // With a diff index that adds the whole block, it is reported.
        StringBuilder sb = new StringBuilder("--- a/M.java\n+++ b/M.java\n@@ -1,0 +2,8 @@\n");
        for (String l : BLOCK.split("\n")) {
            sb.append('+').append(l).append('\n');
        }
        GateResult hit = new DuplicationGate().evaluate(ctx(ws, Map.of(), sb.toString(),
                new ChangedFile("M.java", ChangedFile.ChangeType.MODIFIED, modified)));
        assertFalse(hit.isPassed());
    }

    @Test
    void testFilesSkippedUnlessConfigured(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("src/test/java"));
        Files.writeString(ws.resolve("src/test/java/OldTest.java"), "class OldTest {\n" + BLOCK + "}\n");
        ChangedFile newTest = new ChangedFile("src/test/java/NewTest.java", ChangedFile.ChangeType.ADDED,
                "class NewTest {\n" + BLOCK + "}\n");
        assertTrue(new DuplicationGate().evaluate(ctx(ws, Map.of(), "", newTest)).isPassed());
        assertFalse(new DuplicationGate().evaluate(ctx(ws, Map.of("include_tests", true), "", newTest)).isPassed());
    }

    @Test
    void limitsAndFailuresAreSilent(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("Old.java"), "class Old {\n" + BLOCK + "}\n");
        ChangedFile nf = new ChangedFile("New.java", ChangedFile.ChangeType.ADDED, "class New {\n" + BLOCK + "}\n");
        assertTrue(new DuplicationGate().evaluate(ctx(ws, Map.of("max_files", 0), "", nf)).isPassed());
        assertTrue(new DuplicationGate().evaluate(ctx(ws, Map.of("max_file_bytes", 10), "", nf)).isPassed());
        assertTrue(new DuplicationGate().evaluate(ctx(null, Map.of(), "", nf)).isPassed());
        assertTrue(new DuplicationGate().evaluate(ctx(ws.resolve("missing"), Map.of(), "", nf)).isPassed());
        var prevWalker = DuplicationGate.WALKER;
        DuplicationGate.WALKER = (p, v) -> {
            throw new IOException("boom");
        };
        try {
            assertTrue(new DuplicationGate().evaluate(ctx(ws, Map.of(), "", nf)).isPassed());
        } finally {
            DuplicationGate.WALKER = prevWalker;
        }
        var prevReader = DuplicationGate.READER;
        DuplicationGate.READER = p -> null;
        try {
            assertTrue(new DuplicationGate().evaluate(ctx(ws, Map.of(), "", nf)).isPassed());
        } finally {
            DuplicationGate.READER = prevReader;
        }
        // default reader tolerates unreadable / non-UTF-8 content
        Files.write(ws.resolve("bin.java"), new byte[]{(byte) 0xC3, (byte) 0x28, 0, 1});
        assertNull(DuplicationGate.READER.read(ws.resolve("does-not-exist.java")));
        // changed files with non-code extensions produce no probes at all
        assertTrue(new DuplicationGate().evaluate(ctx(ws, Map.of(), "",
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, BLOCK))).isPassed());
        // absent gate config falls back to defaults
        var context = new AIVContext(ws, new Diff("a", "b", List.of(nf), ""), new AIVConfig(List.of(), Map.of()));
        assertFalse(new DuplicationGate().evaluate(context).isPassed());
    }

    @Test
    void helpers() {
        assertEquals(Set.of("a b", "c"), DuplicationGate.addedLineTexts("+++ b/x\n+ a   b \n+c\n+\n+++ /dev/null\n+zzz\n--- a/x\n"));
        assertTrue(DuplicationGate.addedLineTexts(null).isEmpty());
        assertTrue(DuplicationGate.isTestPath("src/test/java/A.java"));
        assertTrue(DuplicationGate.isTestPath("FooTest.java"));
        assertFalse(DuplicationGate.isTestPath("src/main/Foo.java"));
        assertEquals("", DuplicationGate.norm(null));
        assertEquals(3, DuplicationGate.intVal(3, 1));
        assertEquals(1, DuplicationGate.intVal("x", 1));
    }

    @Test
    void normalizerDropsNoise() {
        List<LineNormalizer.Line> lines = LineNormalizer.normalize("""
                package a;
                import b.C;
                // comment
                # hash comment
                /* block */
                 * inner
                 */
                @Override
                {
                }
                else
                return;
                abc
                    int   x  =  1 ;
                """);
        assertEquals(1, lines.size());
        assertEquals("int x = 1 ;", lines.get(0).text());
        assertEquals(14, lines.get(0).lineNumber());
        assertTrue(LineNormalizer.normalize(null).isEmpty());
        assertTrue(LineNormalizer.normalize("").isEmpty());
        assertEquals("int x = 1 ;\u0001", LineNormalizer.windowKey(lines, 0, 1));
    }

    @Test
    void readerReadsUtf8(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("ok.java"), "class Ok {}", StandardCharsets.UTF_8);
        assertEquals("class Ok {}", DuplicationGate.READER.read(ws.resolve("ok.java")));
    }
}
