/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aiv.plugin.invariant;

import io.github.vaquarkhan.aiv.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class InvariantGateTest {

    @Test
    void getId() {
        assertEquals("invariant", new InvariantGate().getId());
    }

    @Test
    void passesWhenNoJavaFiles() {
        var gate = new InvariantGate();
        var ctx = context(List.of());
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void passesWhenJavaFilesPresent() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "class A {}")));
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void ignoresBlankContentFiles() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("a.java", ChangedFile.ChangeType.MODIFIED, "   \n")));
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void failsOnMergeConflictMarker() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("a.java", ChangedFile.ChangeType.MODIFIED,
                "<<<<<<< HEAD\nclass A {}\n=======\nclass B {}\n>>>>>>> branch\n")));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("Merge conflict marker"));
        assertTrue(r.getFindings().stream().anyMatch(f -> "invariant.merge-conflict".equals(f.getRuleId())));
    }

    @Test
    void failsOnPlaceholderMarker() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("a.java", ChangedFile.ChangeType.MODIFIED,
                "class A { // FIXME: finalize logic\n}\n")));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("Placeholder marker"));
        assertTrue(r.getFindings().stream().anyMatch(f -> "invariant.placeholder".equals(f.getRuleId())));
    }

    @Test
    void ignoresPreExistingPlaceholderWhenNotOnAddedLine() {
        var gate = new InvariantGate();
        String content = "// FIXME: legacy note\nclass A {\n  int x;\n}\n";
        String raw = """
                diff --git a/a.java b/a.java
                --- a/a.java
                +++ b/a.java
                @@ -1,2 +1,3 @@
                 // FIXME: legacy note
                 class A {
                +  int x;
                 }
                """;
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("a.java", ChangedFile.ChangeType.MODIFIED, content)), raw);
        var ctx = new AIVContext(Paths.get("."), diff, new AIVConfig(List.of(), java.util.Map.of()));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnPlaceholderOnlyWhenAddedInDiff() {
        var gate = new InvariantGate();
        String content = "class A {\n  // FIXME: new debt\n}\n";
        String raw = """
                diff --git a/a.java b/a.java
                --- a/a.java
                +++ b/a.java
                @@ -1,2 +1,3 @@
                 class A {
                +  // FIXME: new debt
                 }
                """;
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("a.java", ChangedFile.ChangeType.MODIFIED, content)), raw);
        var ctx = new AIVContext(Paths.get("."), diff, new AIVConfig(List.of(), java.util.Map.of()));
        assertFalse(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnAiProvenanceTrailerInSource() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("mod.py", ChangedFile.ChangeType.ADDED,
                "x = 1\nGenerated-by: ChatGPT\n")));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getFindings().stream().anyMatch(f -> "invariant.ai-provenance".equals(f.getRuleId())));
    }

    @Test
    void failsOnAiEditArtifactInCode() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("mod.py", ChangedFile.ChangeType.MODIFIED,
                "def handler():\n    do_work()\n    # ... rest of the existing code unchanged ...\n")));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("AI edit-artifact"));
        assertTrue(r.getFindings().stream().anyMatch(f -> "invariant.ai-edit-artifact".equals(f.getRuleId())));
    }

    @Test
    void catchesAssistantChatterAndSearchReplaceInCode() {
        var gate = new InvariantGate();
        var ctx1 = context(List.of(new ChangedFile("A.java", ChangedFile.ChangeType.ADDED,
                "// Here's the updated implementation\nclass A {}\n")));
        assertFalse(gate.evaluate(ctx1).isPassed());
        var ctx2 = context(List.of(new ChangedFile("b.js", ChangedFile.ChangeType.MODIFIED,
                "const x = 1;\n<<<<<<< SEARCH\nold\n>>>>>>> REPLACE\n")));
        assertFalse(gate.evaluate(ctx2).isPassed());
    }

    @Test
    void doesNotFlagElisionProseInDocs() {
        // Same phrase in a Markdown doc must NOT be flagged (prose, not code).
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("README.md", ChangedFile.ChangeType.MODIFIED,
                "The rest of the code remains unchanged in this example.\n")));
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void ignoresPreExistingAiEditWhenNotOnAddedLine() {
        var gate = new InvariantGate();
        String content = "# ... rest of the existing code unchanged ...\ndef f():\n  return 1\n";
        String raw = """
                diff --git a/mod.py b/mod.py
                --- a/mod.py
                +++ b/mod.py
                @@ -1,2 +1,3 @@
                 # ... rest of the existing code unchanged ...
                 def f():
                +  return 1
                """;
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("mod.py", ChangedFile.ChangeType.MODIFIED, content)), raw);
        var ctx = new AIVContext(Paths.get("."), diff, new AIVConfig(List.of(), java.util.Map.of()));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnPlaceholderTautologyTest() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("src/test/java/FooTest.java", ChangedFile.ChangeType.ADDED,
                "class FooTest { @Test void t() { assertTrue(true); } }\n")));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getFindings().stream().anyMatch(f -> "invariant.placeholder-test".equals(f.getRuleId())));
    }

    @Test
    void doesNotFlagTautologyOutsideTestPath() {
        var gate = new InvariantGate();
        var ctx = context(List.of(new ChangedFile("src/main/java/Demo.java", ChangedFile.ChangeType.ADDED,
                "class Demo { boolean ok() { return assertTrue(true); } }\n")));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void isTestPathRecognizesCommonLayouts() {
        assertTrue(InvariantGate.isTestPath("src/test/java/FooTest.java"));
        assertTrue(InvariantGate.isTestPath("pkg/foo_test.go"));
        assertTrue(InvariantGate.isTestPath("web/app.spec.ts"));
        assertFalse(InvariantGate.isTestPath("src/main/java/Foo.java"));
        assertFalse(InvariantGate.isTestPath(null));
        assertFalse(InvariantGate.isTestPath("   "));
    }

    @Test
    void addedLineIndexHandlesDeletionAndFallbackPaths() {
        String raw = """
                diff --git a/gone.java b/gone.java
                --- a/gone.java
                +++ /dev/null
                @@ -1 +0,0 @@
                -class Gone {}
                diff --git a/other.java b/other.java
                --- a/other.java
                +++ b/other.java
                @@ -1 +1,2 @@
                 class Other {
                +  int y;
                 }
                """;
        AddedLineIndex idx = AddedLineIndex.fromRawDiff(raw);
        assertTrue(idx.hasData());
        assertTrue(idx.paths().contains("other.java"));
        assertFalse(idx.markerOnAddedLine("other.java", "   ", java.util.regex.Pattern.compile("FIXME")));
        // Path not present in index while other paths are: fall back to full-content scan.
        assertTrue(idx.markerOnAddedLine("missing.java", "// FIXME: debt\n",
                java.util.regex.Pattern.compile("(?i)\\bFIXME\\b")));
    }

    @Test
    void flagsModifiedFileWhenPathMissingFromRawDiffIndex() {
        var gate = new InvariantGate();
        String content = "class A { // FIXME: still scanned\n}\n";
        String raw = """
                diff --git a/other.java b/other.java
                --- a/other.java
                +++ b/other.java
                @@ -1 +1,2 @@
                 class Other {
                +  int y;
                 }
                """;
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("a.java", ChangedFile.ChangeType.MODIFIED, content)), raw);
        var ctx = new AIVContext(Paths.get("."), diff, new AIVConfig(List.of(), java.util.Map.of()));
        assertFalse(gate.evaluate(ctx).isPassed());
    }

    private AIVContext context(List<ChangedFile> files) {
        var diff = new Diff("main", "HEAD", files, "");
        var config = new AIVConfig(List.of(), java.util.Map.of());
        return new AIVContext(Paths.get("."), diff, config);
    }
}
