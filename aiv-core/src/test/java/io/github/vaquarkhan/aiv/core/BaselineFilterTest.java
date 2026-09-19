/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineFilterTest {

    @Test
    void suppressesMatchingFinding(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, """
                # comment
                invariant.placeholder|src/Foo.java
                """);
        var filter = BaselineFilter.load(base);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "src/Foo.java", 1, "FIXME in Foo")));
        var out = filter.apply(raw);
        assertTrue(out.isPassed());
    }

    @Test
    void keepsUnmatchedFinding(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "invariant.placeholder|src/Other.java\n");
        var filter = BaselineFilter.load(base);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "src/Foo.java", 1, "FIXME")));
        var out = filter.apply(raw);
        assertFalse(out.isPassed());
        assertEquals(1, out.getFindings().size());
    }

    @Test
    void partialSuppressKeepsRemaining(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "invariant.placeholder|a.java\n");
        var filter = BaselineFilter.load(base);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "old"),
                Finding.atLine("invariant.ai-edit-artifact", "b.java", 2, "paste")));
        var out = filter.apply(raw);
        assertFalse(out.isPassed());
        assertEquals(1, out.getFindings().size());
        assertEquals("invariant.ai-edit-artifact", out.getFindings().get(0).getRuleId());
    }

    @Test
    void emptyFilterIsNoop() {
        var raw = GateResult.fail("invariant", "x");
        assertFalse(BaselineFilter.empty().apply(raw).isPassed());
    }

    @Test
    void invalidLineThrows(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("bad.txt");
        Files.writeString(base, "only-one-field\n");
        assertThrows(Exception.class, () -> BaselineFilter.load(base));
    }

    @Test
    void suppressesFindingWithMessageSubstring(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "invariant.placeholder|src/Foo.java|legacy\n");
        var filter = BaselineFilter.load(base);
        var hit = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "src/Foo.java", 1, "FIXME legacy note")));
        assertTrue(filter.apply(hit).isPassed());
        var miss = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "src/Foo.java", 1, "FIXME new")));
        assertFalse(filter.apply(miss).isPassed());
    }

    @Test
    void advisoryPartialSuppress(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "density.low|a.java\n");
        var filter = BaselineFilter.load(base);
        var raw = GateResult.advisory("density", "x", List.of(
                Finding.atLine("density.low", "a.java", 1, "low"),
                Finding.atLine("density.entropy", "b.java", 2, "entropy")));
        var out = filter.apply(raw);
        assertFalse(out.isPassed());
        assertFalse(out.blocksCi());
        assertEquals(1, out.getFindings().size());
    }

    @Test
    void suppressesGateWithoutFindingsWhenRuleMatches(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "invariant|any.java\n");
        var filter = BaselineFilter.load(base);
        var raw = GateResult.fail("invariant", "Merge conflict marker found");
        assertTrue(filter.apply(raw).isPassed());
    }

    @Test
    void suppressesGateWithoutFindingsWhenRulePrefixMatches(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "invariant.placeholder|any.java|conflict\n");
        var filter = BaselineFilter.load(base);
        var raw = GateResult.fail("invariant", "Merge conflict marker");
        assertTrue(filter.apply(raw).isPassed());
    }

    @Test
    void gateWithoutFindingsNullMessage(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "invariant|x\n");
        var filter = BaselineFilter.load(base);
        assertTrue(filter.apply(new GateResult("invariant", false, null)).isPassed());
    }


    @Test
    void gateWithoutFindingsUnmatchedStaysFailed(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "syntax.parse|x.java\n");
        var filter = BaselineFilter.load(base);
        var raw = GateResult.fail("invariant", "something");
        assertFalse(filter.apply(raw).isPassed());
    }

    @Test
    void missingFileThrows(@TempDir Path dir) {
        assertThrows(Exception.class, () -> BaselineFilter.load(dir.resolve("nope.txt")));
        assertThrows(Exception.class, () -> BaselineFilter.load(null));
    }

    @Test
    void sizeAndPassedNoop(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("baseline.txt");
        Files.writeString(base, "r|f.java\n");
        var filter = BaselineFilter.load(base);
        assertEquals(1, filter.size());
        assertTrue(filter.apply(GateResult.pass("invariant")).isPassed());
    }

    @Test
    void pathMatchesSuffixBothWays() {
        assertTrue(BaselineFilter.pathMatches("a/b.java", "mod/a/b.java")
                || BaselineFilter.pathMatches("mod/a/b.java", "a/b.java"));
        assertTrue(BaselineFilter.pathMatches("mod/a/b.java", "a/b.java"));
    }
}
