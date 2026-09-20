/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechanicalFixerTest {

    @Test
    void removesConflictAndElisionLines() {
        String in = """
                class A {
                <<<<<<< HEAD
                  int a;
                =======
                  int b;
                >>>>>>> feature
                  // ... existing code ...
                }
                """;
        String out = MechanicalFixer.fixContent(in);
        assertFalse(out.contains("<<<<<<<"));
        assertFalse(out.contains("======="));
        assertFalse(out.contains(">>>>>>>"));
        assertFalse(out.contains("existing code"));
        assertTrue(out.contains("int a;") || out.contains("int b;"));
    }

    @Test
    void unchangedWhenClean() {
        String in = "class A {}\n";
        assertEquals(in, MechanicalFixer.fixContent(in));
        assertEquals("", MechanicalFixer.fixContent(null));
        assertEquals("", MechanicalFixer.fixContent(""));
    }

    @Test
    void applyRewritesFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("Broken.java");
        Files.writeString(f, "<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> z\n", StandardCharsets.UTF_8);
        int n = MechanicalFixer.apply(dir, List.of("Broken.java"));
        assertEquals(1, n);
        String out = Files.readString(f, StandardCharsets.UTF_8);
        assertFalse(out.contains("<<<<<<<"));
    }

    @Test
    void applySkipsMissingAndEscape(@TempDir Path dir) throws Exception {
        assertEquals(0, MechanicalFixer.apply(dir, java.util.Arrays.asList("nope.java", "../escape.java", null, "")));
        assertEquals(0, MechanicalFixer.apply(null, List.of("x")));
        assertEquals(0, MechanicalFixer.apply(dir, null));
    }
}
