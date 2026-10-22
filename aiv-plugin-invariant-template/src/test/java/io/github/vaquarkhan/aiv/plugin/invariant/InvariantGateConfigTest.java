/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.invariant;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repository-specific configuration of the invariant gate. */
class InvariantGateConfigTest {

    private static AIVContext ctx(Map<String, Object> cfg, ChangedFile... files) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig("invariant", true, cfg)), Map.of());
        return new AIVContext(Paths.get("."), new Diff("main", "HEAD", List.of(files), ""), config);
    }

    private static ChangedFile added(String path, String content) {
        return new ChangedFile(path, ChangedFile.ChangeType.ADDED, content);
    }

    @Test
    void placeholderWordsReplaceTheDefaultSet() {
        var gate = new InvariantGate();
        // default: FIXME is a placeholder
        assertFalse(gate.evaluate(ctx(Map.of(), added("a.java", "// FIXME later\n"))).isPassed());
        // custom list: FIXME is allowed, HACK is not
        Map<String, Object> cfg = Map.of("placeholder_words", List.of("HACK"));
        assertTrue(gate.evaluate(ctx(cfg, added("a.java", "// FIXME later\n"))).isPassed());
        GateResult r = gate.evaluate(ctx(cfg, added("a.java", "// HACK: works\n")));
        assertFalse(r.isPassed());
        assertEquals("invariant.placeholder", r.getFindings().get(0).getRuleId());
    }

    @Test
    void disabledRulesAreSkipped() {
        var gate = new InvariantGate();
        String conflict = "<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> b\n";
        assertFalse(gate.evaluate(ctx(Map.of(), added("a.java", conflict))).isPassed());
        Map<String, Object> cfg = Map.of("disabled_rules", List.of("invariant.merge-conflict", "PLACEHOLDER",
                "ai-edit-artifact", "ai-provenance", "placeholder-test"));
        String everything = conflict + "// TBD\n// ... existing code unchanged ...\nGenerated-by: tool\n";
        assertTrue(gate.evaluate(ctx(cfg, added("src/test/java/ATest.java", everything + "assertTrue(true);\n"))).isPassed());
    }

    @Test
    void extraArtifactPatternsApplyToCodeFilesOnly() {
        var gate = new InvariantGate();
        Map<String, Object> cfg = Map.of("extra_artifact_patterns", List.of("TODO: implement actual logic"));
        GateResult code = gate.evaluate(ctx(cfg, added("a.py", "# TODO: Implement Actual Logic here\n")));
        assertFalse(code.isPassed());
        assertEquals("invariant.custom-artifact", code.getFindings().get(0).getRuleId());
        assertTrue(gate.evaluate(ctx(cfg, added("notes.md", "TODO: implement actual logic\n"))).isPassed());
        assertThrows(IllegalArgumentException.class,
                () -> gate.evaluate(ctx(Map.of("extra_artifact_patterns", List.of("(unclosed")), added("a.py", "x"))));
    }

    @Test
    void fileExtensionsRestrictWhichFilesCountAsCode() {
        var gate = new InvariantGate();
        String elision = "// ... existing code unchanged ...\n";
        assertFalse(gate.evaluate(ctx(Map.of(), added("a.java", elision))).isPassed());
        // only Python counts as code now, so the Java file is not scanned for edit artifacts
        assertTrue(gate.evaluate(ctx(Map.of("file_extensions", List.of("py")), added("a.java", elision))).isPassed());
        assertFalse(gate.evaluate(ctx(Map.of("languages", List.of("java")), added("a.java", elision))).isPassed());
    }

    @Test
    void stringListToleratesNullsAndBlanks() {
        assertEquals(List.of("a", "b"), InvariantGate.stringList(Arrays.asList(" a ", null, "", "b")));
        assertTrue(InvariantGate.stringList("not-a-list").isEmpty());
        var s = InvariantGate.Settings.from(Map.of());
        assertTrue(s.enabled("placeholder"));
        assertTrue(s.extraArtifacts().isEmpty());
    }
}
