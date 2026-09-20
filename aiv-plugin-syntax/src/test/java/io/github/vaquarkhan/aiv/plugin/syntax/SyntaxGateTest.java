/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxGateTest {

    private static AIVContext ctx(ChangedFile... files) {
        Diff diff = new Diff("base", "head", List.of(files), "");
        AIVConfig config = new AIVConfig(List.of(), Map.of());
        return new AIVContext(Paths.get("."), diff, config);
    }

    private static ChangedFile file(String path, String content) {
        return new ChangedFile(path, ChangedFile.ChangeType.MODIFIED, content);
    }

    @Test
    void idIsSyntax() {
        assertEquals("syntax", new SyntaxGate().getId());
    }

    @Test
    void passesForValidJavaYamlJson() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(
                file("A.java", "class A {}"),
                file("conf.yml", "a: 1"),
                file("data.json", "{\"a\": 1}")));
        assertTrue(r.isPassed());
    }

    @Test
    void skipsEmptyAndUnknownAndOkValidator() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(
                file("empty.java", "   "),
                file("notes.md", "some text /path/that/is/ignored"),
                file("script.py", "print('ok')")));
        assertTrue(r.isPassed());
    }

    @Test
    void failsForInvalidJava() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(file("Bad.java", "class A { void m( { }")));
        assertFalse(r.isPassed());
        assertEquals(1, r.getFindings().size());
        assertEquals("syntax.parse", r.getFindings().get(0).getRuleId());
    }

    @Test
    void failsForInvalidYamlWithLineFromMark() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(file("broken.yaml", "a: [1, 2")));
        assertFalse(r.isPassed());
        assertTrue(r.getFindings().get(0).getStartLine() >= 1);
    }

    @Test
    void skipsTemplatedYaml() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(
                file("chart/templates/deploy.yaml", "spec:\n{{- $g := deepCopy . -}}\n  key: {{ .Value }}"),
                file("role.yaml", "tasks:\n  - name: x\n    when: {% if foo %}")));
        assertTrue(r.isPassed());
    }

    @Test
    void failsForInvalidJson() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(file("broken.json", "{ \"a\": [1, 2 ")));
        assertFalse(r.isPassed());
    }

    @Test
    void pythonFailureWithNullLineDefaultsToLineOne() {
        SyntaxGate gate = new SyntaxGate(src -> new ValidationResult(false, null, "boom"));
        GateResult r = gate.evaluate(ctx(file("mod.py", "def f(:")));
        assertFalse(r.isPassed());
        assertEquals(1, r.getFindings().get(0).getStartLine());
    }

    @Test
    void routesJsAndGoThroughValidators() {
        SyntaxGate gate = new SyntaxGate(
                src -> ValidationResult.ok(),
                src -> ValidationResult.fail(1, "js-bad"),
                src -> ValidationResult.fail(2, "ts-bad"),
                src -> ValidationResult.fail(3, "go-bad"));
        assertFalse(gate.evaluate(ctx(file("a.js", "const x = ;"))).isPassed());
        assertFalse(gate.evaluate(ctx(file("a.ts", "const x: number = ;"))).isPassed());
        assertFalse(gate.evaluate(ctx(file("a.go", "package main"))).isPassed());
        // JSX skipped (precision-first)
        assertTrue(gate.evaluate(ctx(file("a.jsx", "<div/>"))).isPassed());
    }

    @Test
    void failFactoryNormalizesBlankDetailAndLine() {
        ValidationResult blank = ValidationResult.fail(null, "  ");
        assertFalse(blank.valid());
        assertEquals(1, blank.line());
        assertEquals("unparseable", blank.detail());
        ValidationResult okLine = ValidationResult.fail(0, "bad");
        assertEquals(1, okLine.line());
        assertEquals("bad", okLine.detail());
    }

    @Test
    void pythonFailureWithLineIsReported() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.fail(7, "SyntaxError"));
        GateResult r = gate.evaluate(ctx(file("mod.py", "bad")));
        assertFalse(r.isPassed());
        assertEquals(7, r.getFindings().get(0).getStartLine());
    }

    @Test
    void validateJavaUnparseableFallbackDetail() {
        ValidationResult vr = SyntaxGate.validateJava("this is not java at all %%%");
        assertFalse(vr.valid());
    }

    @Test
    void skipsDotJavaFileThatIsActuallyADockerfile() {
        // A real Airflow file: airflow-e2e-tests/docker/Dockerfile.java is a Dockerfile, not Java.
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(
                file("airflow-e2e-tests/docker/Dockerfile.java",
                        "# a dockerfile that happens to be named .java\nFROM eclipse-temurin:17\n")));
        assertTrue(r.isPassed());
    }

    @Test
    void skipsTsconfigJsonc() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(
                file("airflow-core/src/airflow/ui/tsconfig.app.json",
                        "{\n  \"compilerOptions\": { \"strict\": true },\n}")));
        assertTrue(r.isPassed());
    }

    @Test
    void acceptsMultiDocumentYaml() {
        SyntaxGate gate = new SyntaxGate(src -> ValidationResult.ok());
        GateResult r = gate.evaluate(ctx(
                file("k8s/manifests/localstack.yaml", "apiVersion: v1\nkind: Service\n---\nkind: Pod\n")));
        assertTrue(r.isPassed());
    }

    @Test
    void startsWithHashCoversWhitespaceBomAndCode() {
        assertTrue(SyntaxGate.startsWithHash("   \n  # comment"));
        assertTrue(SyntaxGate.startsWithHash("\uFEFF# bom then hash"));
        assertFalse(SyntaxGate.startsWithHash("class A {}"));
        assertFalse(SyntaxGate.startsWithHash("   "));
    }

    @Test
    void isTsConfigCoversNamedAndPathedAndNegative() {
        assertTrue(SyntaxGate.isTsConfig("tsconfig.app.json"));
        assertTrue(SyntaxGate.isTsConfig("airflow-core/src/airflow/ui/tsconfig.json"));
        assertTrue(SyntaxGate.isTsConfig("dir\\tsconfig.json"));
        assertFalse(SyntaxGate.isTsConfig("data.json"));
    }
}
