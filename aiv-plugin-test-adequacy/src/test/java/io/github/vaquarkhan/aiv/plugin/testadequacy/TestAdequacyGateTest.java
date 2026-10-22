/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.testadequacy;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAdequacyGateTest {

    private static final String ASSERTING_TEST = """
            package x;
            class FooTest {
              @Test
              void works() {
                assertEquals(1, 1);
              }
            }
            """;

    private static final String ASSERTION_FREE_TEST = """
            package x;
            class FooTest {
              @Test
              void works() {
                new Foo().run();
              }
            }
            """;

    private static AIVContext ctx(Path ws, Map<String, Object> cfg, String rawDiff, ChangedFile... files) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(TestAdequacyGate.ID, true, cfg)), Map.of());
        return new AIVContext(ws, new Diff("main", "HEAD", List.of(files), rawDiff), config);
    }

    private static ChangedFile added(String path, String content) {
        return new ChangedFile(path, ChangedFile.ChangeType.ADDED, content);
    }

    private static ChangedFile modified(String path, String content) {
        return new ChangedFile(path, ChangedFile.ChangeType.MODIFIED, content);
    }

    @Test
    void registeredViaServiceLoaderAndId() {
        assertTrue(ServiceLoader.load(io.github.vaquarkhan.aiv.port.QualityGate.class).stream()
                .anyMatch(p -> p.type() == TestAdequacyGate.class));
        assertEquals("test-adequacy", new TestAdequacyGate().getId());
    }

    @Test
    void assertionFreeAddedTestFailsHard(@TempDir Path ws) {
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), "",
                added("src/test/java/x/FooTest.java", ASSERTION_FREE_TEST)));
        assertFalse(r.isPassed());
        assertTrue(r.blocksCi());
        assertEquals(1, r.getFindings().size());
        Finding f = r.getFindings().get(0);
        assertEquals(AssertionScanner.RULE, f.getRuleId());
        assertEquals(4, f.getStartLine());
        assertTrue(r.getMessage().contains("void works()"));
    }

    @Test
    void assertingTestPasses(@TempDir Path ws) {
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), "",
                added("src/test/java/x/FooTest.java", ASSERTING_TEST)));
        assertTrue(r.isPassed());
    }

    @Test
    void productionChangeWithoutAnyTestIsAdvisory(@TempDir Path ws) {
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), "",
                added("src/main/java/x/Foo.java", "class Foo {}"),
                added("README.md", "# docs")));
        assertFalse(r.isPassed());
        assertFalse(r.blocksCi());
        assertEquals(TestAdequacyGate.MISSING_RULE, r.getFindings().get(0).getRuleId());
        assertTrue(r.getMessage().contains("footest"));
    }

    @Test
    void productionChangeCoveredByTestInSameDiffPasses(@TempDir Path ws) {
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), "",
                added("src/main/java/x/Foo.java", "class Foo {}"),
                added("src/test/java/x/FooTest.java", ASSERTING_TEST)));
        assertTrue(r.isPassed());
    }

    @Test
    void productionChangeCoveredByExistingWorkspaceTestPasses(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("tests"));
        Files.writeString(ws.resolve("tests/test_foo.py"), "def test_a():\n    assert True\n");
        Files.createDirectories(ws.resolve("node_modules/pkg"));
        Files.writeString(ws.resolve("node_modules/pkg/bar.test.js"), "it('x', () => {})");
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), "",
                modified("pkg/foo.py", "def foo():\n    return 1\n"),
                modified("pkg/bar.py", "def bar():\n    return 1\n")));
        // foo covered by tests/test_foo.py; bar's only candidate lives under node_modules (skipped)
        assertFalse(r.isPassed());
        assertEquals(1, r.getFindings().size());
        assertEquals("pkg/bar.py", r.getFindings().get(0).getFilePath());
    }

    @Test
    void hardAndSoftFindingsAreCombinedIntoFail(@TempDir Path ws) {
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), "",
                added("src/main/java/x/Foo.java", "class Foo {}"),
                added("src/test/java/x/BarTest.java", ASSERTION_FREE_TEST)));
        assertTrue(r.blocksCi());
        assertEquals(2, r.getFindings().size());
    }

    @Test
    void configCanDisableEachCheck(@TempDir Path ws) {
        var files = new ChangedFile[]{
                added("src/main/java/x/Foo.java", "class Foo {}"),
                added("src/test/java/x/BarTest.java", ASSERTION_FREE_TEST)};
        assertTrue(new TestAdequacyGate().evaluate(ctx(ws,
                Map.of("check_assertions", false, "require_tests", "false"), "", files)).isPassed());
        GateResult onlySoft = new TestAdequacyGate().evaluate(ctx(ws,
                Map.of("check_assertions", "false"), "", files));
        assertFalse(onlySoft.blocksCi());
        assertFalse(onlySoft.isPassed());
    }

    @Test
    void nonProductionCodeFilesAreNotAskedForTests(@TempDir Path ws) {
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), "",
                added("pkg/__init__.py", "from .a import b\n"),
                added("src/main/java/x/package-info.java", "package x;"),
                added("scripts/release.py", "print('x')\n")));
        assertTrue(r.isPassed());
    }

    @Test
    void gateAbsentFromConfigUsesDefaults(@TempDir Path ws) {
        var config = new AIVConfig(List.of(), Map.of());
        var context = new AIVContext(ws, new Diff("a", "b", List.of(
                added("src/test/java/x/FooTest.java", ASSERTION_FREE_TEST)), ""), config);
        assertFalse(new TestAdequacyGate().evaluate(context).isPassed());
    }

    @Test
    void addedLinesScopeToModifiedFiles(@TempDir Path ws) {
        String content = """
            class FooTest {
              @Test
              void old() {
                new Foo().run();
              }
              @Test
              void fresh() {
                new Foo().run();
              }
            }
            """;
        String rawDiff = """
            diff --git a/src/test/java/FooTest.java b/src/test/java/FooTest.java
            --- a/src/test/java/FooTest.java
            +++ b/src/test/java/FooTest.java
            @@ -5,0 +6,4 @@
            +  @Test
            +  void fresh() {
            +    new Foo().run();
            +  }
            """;
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of(), rawDiff,
                modified("src/test/java/FooTest.java", content)));
        assertEquals(1, r.getFindings().size());
        assertEquals(7, r.getFindings().get(0).getStartLine());
    }

    @Test
    void workspaceIndexHonorsLimitsAndFailures(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve("tests"));
        Files.writeString(ws.resolve("tests/test_a.py"), "x");
        Files.writeString(ws.resolve("tests/test_b.py"), "x");
        Files.writeString(ws.resolve("tests/notes.txt"), "x");
        assertEquals(Set.of("test_a", "test_b"), TestAdequacyGate.indexWorkspaceTestStems(ws, 100));
        assertEquals(0, TestAdequacyGate.indexWorkspaceTestStems(ws, 0).size()); // budget exhausted at once
        assertTrue(TestAdequacyGate.indexWorkspaceTestStems(null, 10).isEmpty());
        assertTrue(TestAdequacyGate.indexWorkspaceTestStems(ws.resolve("nope"), 10).isEmpty());
        var prev = TestAdequacyGate.WALKER;
        TestAdequacyGate.WALKER = (p, v) -> {
            throw new IOException("boom");
        };
        try {
            assertTrue(TestAdequacyGate.indexWorkspaceTestStems(ws, 10).isEmpty());
        } finally {
            TestAdequacyGate.WALKER = prev;
        }
        GateResult r = new TestAdequacyGate().evaluate(ctx(ws, Map.of("max_workspace_files", 0), "",
                modified("pkg/a.py", "def a():\n    return 1\n")));
        assertFalse(r.isPassed());
    }

    @Test
    void helpersParseLooseTypes() {
        assertTrue(TestAdequacyGate.bool(null, true));
        assertFalse(TestAdequacyGate.bool("no", true));
        assertTrue(TestAdequacyGate.bool(Boolean.TRUE, false));
        assertEquals(7, TestAdequacyGate.intVal(7L, 1));
        assertEquals(1, TestAdequacyGate.intVal("7", 1));
    }

    // ---------------- TestPaths ----------------

    @Test
    void pathClassification() {
        assertTrue(TestPaths.isCodeFile("a/B.java"));
        assertTrue(TestPaths.isCodeFile("a\\b.py"));
        assertFalse(TestPaths.isCodeFile("types.d.ts"));
        assertFalse(TestPaths.isCodeFile("README.md"));
        assertFalse(TestPaths.isCodeFile(null));

        assertTrue(TestPaths.isTestPath("src/test/java/A.java"));
        assertTrue(TestPaths.isTestPath("tests/anything.py"));
        assertTrue(TestPaths.isTestPath("pkg/test_foo.py"));
        assertTrue(TestPaths.isTestPath("pkg/foo_test.go"));
        assertTrue(TestPaths.isTestPath("FooSpec.scala"));
        assertTrue(TestPaths.isTestPath("ui/foo.spec.ts"));
        assertTrue(TestPaths.isTestPath("lib/foo_spec.rb"));
        assertFalse(TestPaths.isTestPath("src/main/java/Foo.java"));

        assertTrue(TestPaths.isProductionCandidate("src/main/java/Foo.java"));
        assertFalse(TestPaths.isProductionCandidate("src/main/java/package-info.java"));
        assertFalse(TestPaths.isProductionCandidate("pkg/__init__.py"));
        assertFalse(TestPaths.isProductionCandidate("src/generated/Foo.java"));
        assertFalse(TestPaths.isProductionCandidate("docs/conf.txt"));
        assertFalse(TestPaths.isProductionCandidate("src/test/java/FooTest.java"));

        assertEquals("Foo.java", TestPaths.basename("Foo.java"));
        assertEquals("Makefile", TestPaths.stem("Makefile"));
        assertEquals("", TestPaths.norm(null));
        Set<String> stems = TestPaths.candidateTestStems("a/OrderService.java");
        assertTrue(stems.contains("orderservicetest"));
        assertTrue(stems.contains("test_orderservice"));
        assertTrue(stems.contains("orderservice.spec"));
    }

    // ---------------- AssertionScanner ----------------

    @Test
    void javaScannerEdgeCases() {
        AddedLines none = AddedLines.parse(null);
        // expected= on the annotation counts as an exception assertion; stacked annotations are skipped
        String ok = """
            @Test(expected = IllegalStateException.class)
            @DisplayName("x")
            void boom() {
              svc.run();
            }
            """;
        assertTrue(AssertionScanner.scan("ATest.java", ok, true, none).isEmpty());
        // annotation at EOF, and signature with no brace within 3 lines (interface default / abstract)
        String tail = "class T {\n  @Test\n";
        assertTrue(AssertionScanner.scan("ATest.java", tail, true, none).isEmpty());
        assertTrue(AssertionScanner.scan("ATest.java", "@Test", true, none).isEmpty());
        String abstractSig = "@Test\nvoid a();\n\n\n\nx\n";
        assertTrue(AssertionScanner.scan("ATest.java", abstractSig, true, none).isEmpty());
        // Kotlin, unmatched brace to EOF, no assertion
        String kt = "@Test\nfun t() {\n  svc.run()\n";
        assertEquals(1, AssertionScanner.scan("ATest.kt", kt, true, none).size());
        assertTrue(AssertionScanner.scan("ATest.java", "   ", true, none).isEmpty());
        assertTrue(AssertionScanner.scan("ATest.java", null, true, none).isEmpty());
        // MODIFIED file without diff index: nothing is "added", nothing flagged
        assertTrue(AssertionScanner.scan("ATest.java", ASSERTION_FREE_TEST, false, none).isEmpty());
    }

    @Test
    void pythonScanner() {
        AddedLines none = AddedLines.parse(null);
        String py = """
            import pytest

            def test_ok():
                with pytest.raises(ValueError):
                    boom()

            async def test_empty():
                x = 1

                y = 2
            def helper():
                pass
            class T:
                def test_method(self):
                    self.assertEqual(1, 1)
            """;
        List<Finding> out = AssertionScanner.scan("tests/test_x.py", py, true, none);
        assertEquals(1, out.size());
        assertEquals(7, out.get(0).getStartLine());
        // Regression from apache/airflow#73124 (bench): multi-line parametrized signature whose "):" sits at
        // the def's indentation must not end the body early (was a false positive).
        String multiLine = """
            @pytest.mark.parametrize("x", [1, 2])
            def test_projection(
                python_versions, backend_versions
            ):
                actual = [1]
                assert set(actual) == {1}
            """;
        assertTrue(AssertionScanner.scan("tests/test_m.py", multiLine, true, none).isEmpty());
        assertEquals(0, AssertionScanner.parenDepth("a(b(c))"));
        // not added -> skipped
        AddedLines idx = AddedLines.parse("+++ b/tests/test_x.py\n+def test_ok():\n");
        assertTrue(AssertionScanner.scan("tests/test_x.py", py, false, idx).isEmpty());
    }

    @Test
    void jsAndGoScanner() {
        AddedLines none = AddedLines.parse(null);
        String js = """
            describe('x', () => {
              it('asserts', () => {
                expect(1).toBe(1);
              });
              test.only('empty', async () => {
                await run();
              });
              it.each([1])('param %i', (n) => {
                run(n);
              });
            });
            """;
        List<Finding> out = AssertionScanner.scan("x.test.js", js, true, none);
        assertEquals(2, out.size());
        assertEquals(5, out.get(0).getStartLine());
        assertEquals(8, out.get(1).getStartLine());

        String go = """
            func TestOk(t *testing.T) {
              if got != want {
                t.Errorf("bad")
              }
            }
            func TestEmpty(t *testing.T) {
              run()
            }
            """;
        List<Finding> goOut = AssertionScanner.scan("a_test.go", go, true, none);
        assertEquals(1, goOut.size());
        assertEquals(6, goOut.get(0).getStartLine());
        // not added
        assertTrue(AssertionScanner.scan("a_test.go", go, false, none).isEmpty());
    }

    @Test
    void braceBodyHelper() {
        assertNull(AssertionScanner.braceBody(new String[]{"a", "b", "c", "d {", "}"}, 0));
        assertEquals("{ x }", AssertionScanner.braceBody(new String[]{"void a()", "{ x }"}, 0));
        assertNotNull(AssertionScanner.braceBody(new String[]{"{", "open"}, 0));
        assertNull(AssertionScanner.braceBody(new String[]{"no", "brace"}, 0));
    }

    // ---------------- AddedLines ----------------

    @Test
    void addedLinesParsing() {
        AddedLines a = AddedLines.parse("""
            --- a/x.py
            +++ b/x.py
            @@ -1 +1,2 @@
            +added
             context
            +++ /dev/null
            +ignored
            +++ b/dir\\y.py
            +other
            """);
        assertTrue(a.hasData());
        assertTrue(a.isAdded("x.py", "  added ", false));
        assertFalse(a.isAdded("x.py", "context", false));
        assertTrue(a.isAdded("dir/y.py", "other", false));
        assertTrue(a.isAdded("unknown.py", "anything", true));
        assertFalse(a.isAdded(null, null, false));
        AddedLines empty = AddedLines.parse("   ");
        assertFalse(empty.hasData());
        assertTrue(empty.isAdded("x", "y", true));
        assertFalse(empty.isAdded("x", "y", false));
    }
}
