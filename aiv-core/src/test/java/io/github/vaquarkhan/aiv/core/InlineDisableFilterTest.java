/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineDisableFilterTest {

    @Test
    void nextLineSuppressesMatchingFinding() {
        String content = """
                // aiv-disable-next-line invariant.placeholder
                // FIXME: intentional
                class A {}
                """;
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 2, "FIXME")));
        var out = InlineDisableFilter.apply(raw, ctx);
        assertTrue(out.isPassed());
    }

    @Test
    void nextLineWithoutRuleSuppressesAnyRuleOnNextLine() {
        String content = """
                # aiv-disable-next-line
                secret = "x"
                """;
        var ctx = context("a.env", content);
        var raw = GateResult.fail("security", "x", List.of(
                Finding.atLine("security.aws-key", "a.env", 2, "key")));
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void fileLevelDisableSuppressesRuleAnywhere() {
        String content = """
                // aiv-disable invariant.placeholder
                class A {
                  // FIXME
                }
                """;
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "FIXME")));
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void sameLineDisableWorks() {
        String content = "x = 1  // aiv-disable-line density.low\n";
        var ctx = context("a.py", content);
        var raw = GateResult.fail("density", "x", List.of(
                Finding.atLine("density.low", "a.py", 1, "low")));
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void keepsFindingWithoutDirective() {
        String content = "class A { // FIXME }\n";
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "FIXME")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void partialSuppressKeepsOtherFindings() {
        String content = """
                // aiv-disable-next-line invariant.placeholder
                // FIXME
                // ... existing code unchanged ...
                """;
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 2, "FIXME"),
                Finding.atLine("invariant.ai-edit-artifact", "a.java", 3, "elision")));
        var out = InlineDisableFilter.apply(raw, ctx);
        assertFalse(out.isPassed());
        assertEquals(1, out.getFindings().size());
        assertEquals("invariant.ai-edit-artifact", out.getFindings().get(0).getRuleId());
    }

    @Test
    void readsFromWorkspaceWhenNotInDiff(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("w.java");
        Files.writeString(f, """
                // aiv-disable invariant.placeholder
                class W {}
                """);
        var diff = new Diff("a", "b", List.of(), "");
        var ctx = new AIVContext(dir, diff, new AIVConfig(List.of(), Map.of()));
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "w.java", 1, "x")));
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void prefixStarMatchesFamily() {
        String content = "// aiv-disable security.*\nAWS_KEY=AKIA0000000000000000\n";
        var ctx = context("s.env", content);
        var raw = GateResult.fail("security", "x", List.of(
                Finding.atLine("security.aws-key", "s.env", 2, "aws")));
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void passedResultUnchanged() {
        var ctx = context("a.java", "class A {}\n");
        var raw = GateResult.pass("invariant");
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void nullRawOrContextReturnedAsIs() {
        assertEquals(null, InlineDisableFilter.apply(null, context("a.java", "x")));
        var fail = GateResult.fail("g", "m", List.of(Finding.atLine("r", "a.java", 1, "m")));
        assertEquals(fail, InlineDisableFilter.apply(fail, null));
    }

    @Test
    void emptyFindingsUnchanged() {
        var ctx = context("a.java", "class A {}\n");
        var raw = GateResult.fail("invariant", "x", List.of());
        assertEquals(raw, InlineDisableFilter.apply(raw, ctx));
    }

    @Test
    void partialSuppressOnAdvisoryKeepsAdvisory() {
        String content = """
                // aiv-disable-next-line density.low
                x = 1
                y = 2
                """;
        var ctx = context("a.py", content);
        var raw = GateResult.advisory("density", "x", List.of(
                Finding.atLine("density.low", "a.py", 2, "low"),
                Finding.atLine("density.high", "a.py", 3, "high")));
        var out = InlineDisableFilter.apply(raw, ctx);
        assertFalse(out.isPassed());
        assertFalse(out.blocksCi());
        assertEquals(1, out.getFindings().size());
    }

    @Test
    void blankContentDoesNotSuppress() {
        var ctx = context("a.java", "   \n");
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "x")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void missingContentDoesNotSuppress() {
        var diff = new Diff("a", "b", List.of(
                new ChangedFile("other.java", ChangedFile.ChangeType.MODIFIED, "class O {}")), "");
        var ctx = new AIVContext(Path.of("."), diff, new AIVConfig(List.of(), Map.of()));
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "missing.java", 1, "x")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void blockCommentDisableAndCommaRules() {
        String content = "/* aiv-disable-next-line invariant.placeholder,invariant.other */\n// FIXME\n";
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 2, "FIXME")));
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void commaOnlyRuleListMeansAll() {
        // group 2 is only commas → parseRules yields empty → treated as *
        String content = "// aiv-disable-next-line ,,,\n// FIXME\n";
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 2, "FIXME")));
        assertTrue(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void nonMatchingRuleIdKept() {
        String content = "// aiv-disable density.low\nclass A {}\n";
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 2, "x")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void prefixStarDoesNotMatchWrongFamily() {
        String content = "// aiv-disable security.*\nclass A {}\n";
        var ctx = context("a.java", content);
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 2, "x")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void nullDiffYieldsNoIndexSuppress() {
        var ctx = new AIVContext(Path.of("."), null, new AIVConfig(List.of(), Map.of()));
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "x")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void readWorkspaceRejectsPathEscape(@TempDir Path dir) throws Exception {
        Path outside = Files.createTempFile("aiv-out", ".java");
        Files.writeString(outside, "// aiv-disable invariant.placeholder\nclass X {}\n");
        try {
            var diff = new Diff("a", "b", List.of(), "");
            var ctx = new AIVContext(dir, diff, new AIVConfig(List.of(), Map.of()));
            String escape = ".." + java.io.File.separator + outside.getFileName();
            // Prefer a relative path that normalizes outside workspace
            Path sibling = dir.getParent().resolve(outside.getFileName());
            if (!Files.exists(sibling)) {
                Files.copy(outside, sibling);
            }
            var raw = GateResult.fail("invariant", "x", List.of(
                    Finding.atLine("invariant.placeholder", "../" + outside.getFileName(), 1, "x")));
            assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void readWorkspaceMissingFile(@TempDir Path dir) {
        var diff = new Diff("a", "b", List.of(), "");
        var ctx = new AIVContext(dir, diff, new AIVConfig(List.of(), Map.of()));
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "nope.java", 1, "x")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    @Test
    void readWorkspaceIoFailureReturnsNull(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("boom.java");
        Files.writeString(f, "// aiv-disable invariant.placeholder\nclass L {}\n");
        var previous = InlineDisableFilter.CONTENT_LOADER;
        InlineDisableFilter.CONTENT_LOADER = p -> {
            throw new IllegalStateException("forced");
        };
        try {
            var diff = new Diff("a", "b", List.of(), "");
            var ctx = new AIVContext(dir, diff, new AIVConfig(List.of(), Map.of()));
            var raw = GateResult.fail("invariant", "x", List.of(
                    Finding.atLine("invariant.placeholder", "boom.java", 1, "x")));
            assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
        } finally {
            InlineDisableFilter.CONTENT_LOADER = previous;
        }
    }

    @Test
    void nullContentInChangedFileSkipped() {
        var file = new ChangedFile("a.java", ChangedFile.ChangeType.MODIFIED, null);
        var diff = new Diff("main", "HEAD", List.of(file), "");
        var ctx = new AIVContext(Path.of("."), diff, new AIVConfig(List.of(), Map.of()));
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "x")));
        assertFalse(InlineDisableFilter.apply(raw, ctx).isPassed());
    }

    private static AIVContext context(String path, String content) {
        var file = new ChangedFile(path, ChangedFile.ChangeType.MODIFIED, content);
        var diff = new Diff("main", "HEAD", List.of(file), "");
        return new AIVContext(Path.of("."), diff, new AIVConfig(List.of(), Map.of()));
    }
}
