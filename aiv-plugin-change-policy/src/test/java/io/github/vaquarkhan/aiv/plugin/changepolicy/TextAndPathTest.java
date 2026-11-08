/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextAndPathTest {

    private static boolean g(String glob, String path) {
        return Glob.any(path, Glob.compileAll(List.of(glob)));
    }

    @Test
    void globsAreStrict() {
        assertTrue(g("docs/**", "docs/a/b.md"));
        assertFalse(g("docs/**", "src/docs_util.py"), "no substring fallback");
        assertTrue(g("**/tests/**", "tests/a.py"));
        assertTrue(g("**/tests/**", "providers/x/tests/a.py"));
        assertFalse(g("**/tests/**", "src/pytests.py"));
        assertTrue(g("*.lock", "a/b/c.lock"), "slash-free pattern matches the file name anywhere");
        assertTrue(g("test_?.py", "x/test_a.py"));
        assertFalse(g("src/*.py", "src/a/b.py"));
        assertTrue(g("src/**", "src/a/b.py"));
        assertTrue(g("a+b(c).txt", "a+b(c).txt"), "regex metacharacters are literal");
        assertFalse(Glob.any("x", List.of()));
    }

    @Test
    void diffStatsReadsOnlyHunkLines() {
        String raw = "diff --git a/a.py b/a.py\r\n--- a/a.py\n+++ b/a.py\n@@ -1,2 +1,2 @@\n-old\n+new\n+++plus\n ctx\n"
                + "diff --git a/b.py b/b.py\nnew file mode 100644\n--- /dev/null\n+++ b/b.py\n"
                + "diff --git weird\n@@\n+x\n";
        Map<String, DiffStats.FileDiff> m = DiffStats.parse(raw);
        assertEquals(List.of("new", "++plus"), m.get("a.py").added());
        assertEquals(List.of("old"), m.get("a.py").removed());
        assertEquals(0, m.get("b.py").changed(), "headers are not content");
        assertEquals(List.of("x"), m.get("weird").added());
        assertTrue(DiffStats.parse("@@\n+orphan\n").isEmpty(), "hunk without a file header is ignored");
    }

    @Test
    void whitespaceOnlyIgnoresMovedBlocks() {
        assertTrue(new DiffStats.FileDiff(List.of("a  =  1", ""), List.of("a=1")).whitespaceOnly());
        assertFalse(new DiffStats.FileDiff(List.of("b", "a"), List.of("a", "b")).whitespaceOnly(), "reordering is a change");
        assertFalse(DiffStats.FileDiff.EMPTY.whitespaceOnly());
    }

    @Test
    void classifierUsesProjectGlobsFirstAndAreaFallback() {
        Classifier c = new Classifier(Map.of("build-infra", List.of("dev/breeze/**"), "test", List.of("**/*.test.tsx")),
                new java.util.LinkedHashMap<>(Map.of("ui", List.of("ui/**"))), 2);
        assertEquals("lockfile", c.category("ui/package-lock.json"));
        assertEquals("build-infra", c.category("dev/breeze/x.py"));
        assertEquals("ci", c.category(".github/workflows/ci.yml"));
        assertEquals("ai-instruction", c.category("AGENTS.md"));
        assertEquals("dependency-manifest", c.category("providers/x/pyproject.toml"));
        assertEquals("test", c.category("ui/src/a.test.tsx"));
        assertEquals("test", c.category("src/test/java/FooTest.java"));
        assertEquals("docs", c.category("docs/x.rst"));
        assertEquals("config", c.category("a/b.yaml"));
        assertEquals("source", c.category("airflow/models/dag.py"));
        assertEquals("ui", c.area("ui/src/a.tsx"));
        assertEquals("airflow/models", c.area("airflow/models/dag.py"));
        assertEquals("a", c.area("a/b.py"));
        assertEquals("(root)", c.area("setup.py"));
    }

    @Test
    void intentClasses() {
        assertEquals("typo", MetaText.intent("Fix typo in docs"));
        assertEquals("docs", MetaText.intent("Update README"));
        assertEquals("bump", MetaText.intent("Bump ruff to 0.6"));
        assertEquals("test", MetaText.intent("Add unit tests for hook"));
        assertEquals("refactor", MetaText.intent("Refactor scheduler loop"));
        assertEquals("fix", MetaText.intent("Fix race in triggerer"));
        assertEquals("feature", MetaText.intent("Add support for X"));
        assertEquals("unknown", MetaText.intent("Scheduler loop"));
    }

    @Test
    void bodyFacts() {
        List<Pattern> jira = List.of(Pattern.compile("AIRFLOW-\\d+"));
        assertTrue(MetaText.linkedIssue("closes: #123", List.of()));
        assertTrue(MetaText.linkedIssue("see https://github.com/apache/airflow/issues/9", List.of()));
        assertFalse(MetaText.linkedIssue("color &#123; and a#1", List.of()));
        assertTrue(MetaText.linkedIssue("AIRFLOW-42", jira));
        assertFalse(MetaText.linkedIssue("nothing", jira));

        String body = "Intro <!-- hidden words here -->\nWas generative AI tooling used?\nreal text";
        String clean = MetaText.clean(body, List.of(Pattern.compile("^Was generative")));
        assertEquals("Intro \nreal text", clean);
        assertEquals(3, MetaText.words(clean));

        String tpl = "## Why\nBecause it breaks.\n## How was this tested\n<!-- fill -->\n**Notes**\nx";
        assertEquals(List.of("How was this tested", "Missing"),
                MetaText.missingSections(MetaText.clean(tpl, List.of()), List.of("Why", "How was this tested", "Missing")));
        assertEquals(List.of(), MetaText.missingSections("**Why**\nlast section text", List.of("Why")));
    }

    @Test
    void testClaims() {
        assertEquals(22, MetaText.testsClaimCount("Added 22 test cases, 22/22 passed (100%)"));
        assertEquals(5, MetaText.testsClaimCount("This PR has 5 new unit tests"));
        assertEquals(12, MetaText.testsClaimCount("12 tests were added"));
        assertEquals(0, MetaText.testsClaimCount("Unit tests for all operators (127 tests passing). 1111 passed"),
                "tests run are not tests added");
        assertEquals(0, MetaText.testsClaimCount("Python 3.12, fixes #12 new tests, 3 test files"));
        assertTrue(MetaText.testsAddedClaim("I added unit tests for the hook"));
        assertFalse(MetaText.testsAddedClaim("No new tests added, behaviour unchanged"));
        assertFalse(MetaText.testsAddedClaim("- [ ] Added tests\nnothing else"), "unchecked template items claim nothing");
        assertFalse(MetaText.testsAddedClaim("Refactors the loop"));
        assertFalse(MetaText.testsAddedClaim("Happy to add tests if you want"), "an offer is not a claim");
        assertEquals(4, MetaText.testFunctions(List.of("def test_a():", "    async def test_b(x):", "@Test",
                "func TestC(t *testing.T) {", "def helper():")));
        assertEquals(2, MetaText.testFunctions(List.of("it('works', () => {", "test.each([1])('x', () => {")));
    }

    @Test
    void disclosureConsistency() {
        assertTrue(MetaText.disclosureMismatch("- [ ] Yes\nGenerated-by: Claude Code"));
        assertTrue(MetaText.disclosureMismatch("- [ ] Yes\nGenerated-by: [Cursor]"), "tool named in brackets");
        assertFalse(MetaText.disclosureMismatch("- [x] Yes: Cursor IDE\nGenerated-by: [Tool Name]"),
                "ticked box with the tool named inline is a normal answer");
        assertFalse(MetaText.disclosureMismatch("- [x] Yes\nGenerated-by: Claude Code"));
        assertFalse(MetaText.disclosureMismatch("- [ ] Yes\nGenerated-by: [Tool Name]"), "template placeholder");
        assertFalse(MetaText.disclosureMismatch("- [ ] Yes\nGenerated-by: [Tool Name and Version]"), "Flink placeholder");
        assertFalse(MetaText.disclosureMismatch("Generated-by: Copilot"), "no checkbox in this template");
    }
}
