/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocIntegrityGateTest {

    @Test
    void passesWhenNoDocFiles() {
        var gate = new DocIntegrityGate();
        var ctx = context(Path.of("."), List.of(
                new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "public class Foo {}")
        ));
        GateResult r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void passesWhenDocFileHasValidPath(@TempDir Path dir) throws Exception {
        Path readme = dir.resolve("docs/README.md");
        Files.createDirectories(readme.getParent());
        Files.writeString(readme, "See the docs");
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("docs/README.md", ChangedFile.ChangeType.ADDED, "See docs at docs/")
        ));
        GateResult r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void failsWhenRequiredMentionMissing(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/doc-rules.yaml"), """
                doc_constraints:
                  - id: proto-rule
                    trigger_keywords: [".proto"]
                    required_mentions: ["connect-gen-protos.sh"]
                    scope: [README.md]
                """);
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "We use .proto files for schema.")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("connect-gen-protos.sh"));
    }

    @Test
    void gateIdIsDocIntegrity() {
        var gate = new DocIntegrityGate();
        assertEquals("doc-integrity", gate.getId());
    }

    @Test
    void passesWhenDocFileTxt() {
        var gate = new DocIntegrityGate();
        var ctx = context(Path.of("."), List.of(
                new ChangedFile("notes.txt", ChangedFile.ChangeType.ADDED, "Simple text")
        ));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenDocFileRst() {
        var gate = new DocIntegrityGate();
        var ctx = context(Path.of("."), List.of(
                new ChangedFile("index.rst", ChangedFile.ChangeType.ADDED, "Restructured text")
        ));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenRequiredMentionPresent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/doc-rules.yaml"), """
                doc_constraints:
                  - id: golden-rule
                    trigger_keywords: ["golden file"]
                    required_mentions: ["SPARK_GENERATE_GOLDEN_FILES"]
                    scope: [README.md]
                """);
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "For golden file tests set SPARK_GENERATE_GOLDEN_FILES=1.")
        ));
        GateResult r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void readsRulesFromFrozenPolicyWorkspace(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("source");
        Path policy = dir.resolve("policy");
        Files.createDirectories(source.resolve(".aiv"));
        Files.createDirectories(policy.resolve(".aiv"));
        Files.writeString(source.resolve(".aiv/doc-rules.yaml"), "doc_constraints: []\n");
        Files.writeString(policy.resolve(".aiv/doc-rules.yaml"), """
                doc_constraints:
                  - id: frozen
                    trigger_keywords: ["golden file"]
                    required_mentions: ["REQUIRED_FLAG"]
                    scope: [README.md]
                """);
        Diff diff = new Diff("base", "head", List.of(new ChangedFile("README.md",
                ChangedFile.ChangeType.ADDED, "This golden file workflow is documented.")), "");
        AIVConfig config = new AIVConfig(List.of(new AIVConfig.GateConfig("doc-integrity", true,
                Map.of("rules_path", ".aiv/doc-rules.yaml"))), Map.of());
        GateResult result = new DocIntegrityGate().evaluate(new AIVContext(source, policy, diff, config, Map.of()));
        assertFalse(result.isPassed());
        assertTrue(ruleIds(result).contains("doc-integrity.required-mention"));
    }

    @Test
    void usesDefaultRulesPathWhenGateConfigOmitsKey(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/doc-rules.yaml"), "doc_constraints: []\ncanonical_commands: []\n");
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "Hello")
        ), Map.of());
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnFileExistenceViolation(@TempDir Path dir) throws Exception {
        var gate = new DocIntegrityGate();
        Files.createDirectories(dir.resolve(".aiv"));
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED,
                        "See nonexistent/path/to/file.txt for info")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(ruleIds(r).contains("doc-integrity.file-existence"));
    }

    @Test
    void failsOnCrossReferenceViolation(@TempDir Path dir) throws Exception {
        var gate = new DocIntegrityGate();
        Files.createDirectories(dir.resolve(".aiv"));
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "Read the README in nonexistent/folder")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(ruleIds(r).contains("doc-integrity.cross-ref"));
    }

    @Test
    void failsOnBrokenMarkdownLink(@TempDir Path dir) throws Exception {
        var gate = new DocIntegrityGate();
        Files.createDirectories(dir.resolve(".aiv"));
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "[nope](./missing-target.md)")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(ruleIds(r).contains("doc-integrity.markdown-link"));
    }

    @Test
    void failsOnIncompleteCanonicalCommand(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/doc-rules.yaml"), """
                doc_constraints: []
                canonical_commands:
                  - id: verify
                    pattern: "mvn verify"
                    required_flags: []
                    required_followup: [install]
                    required_commands: []
                """);
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "Run mvn verify on CI")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(ruleIds(r).contains("doc-integrity.command"));
    }

    @Test
    void failsOnPathFabrication(@TempDir Path dir) throws Exception {
        var gate = new DocIntegrityGate();
        Files.createDirectories(dir.resolve(".aiv"));
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "Setup at ~/.virtualenvs/pyspark")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(ruleIds(r).contains("doc-integrity.path-fabrication"));
        assertTrue(r.getMessage().contains("[P1]"));
    }

    @Test
    void reportsMultipleViolationsFromSingleDocFile(@TempDir Path dir) throws Exception {
        var gate = new DocIntegrityGate();
        Files.createDirectories(dir.resolve(".aiv"));
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED,
                        "[missing](./missing.md)\nRefer to file does/not/exist.txt")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(ruleIds(r).contains("doc-integrity.markdown-link"));
        assertTrue(ruleIds(r).contains("doc-integrity.file-existence"));
    }

    private static List<String> ruleIds(GateResult r) {
        return r.getFindings().stream().map(Finding::getRuleId).collect(Collectors.toList());
    }

    private AIVContext context(Path workspace, List<ChangedFile> files) {
        return context(workspace, files, Map.of("rules_path", ".aiv/doc-rules.yaml"));
    }

    private AIVContext context(Path workspace, List<ChangedFile> files, Map<String, Object> gateConfig) {
        var diff = new Diff("main", "HEAD", files, "", 0, 0, null, false);
        var gates = List.of(new AIVConfig.GateConfig("doc-integrity", true, gateConfig));
        var config = new AIVConfig(gates, Map.of());
        return new AIVContext(workspace, diff, config);
    }
}
