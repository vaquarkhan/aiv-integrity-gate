/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SarifReportWriterTest {

    @Test
    void writesEmptyResultsWhenAllGatesPass(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("pass.sarif");
        SarifReportWriter.write(new AIVResult(true, List.of(GateResult.pass("density"))), out, "1.0");
        String s = Files.readString(out);
        assertTrue(s.contains("\"results\": []"));
        assertTrue(s.contains("\"doctorMode\": false"));
    }

    @Test
    void writesDoctorModeInRunProperties(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("doctor.sarif");
        SarifReportWriter.write(new AIVResult(true, List.of(GateResult.pass("density")),
                List.of("[DOCTOR] Informational")), out, "1.0");
        assertTrue(Files.readString(out).contains("\"doctorMode\": true"));
    }

    @Test
    void writesGateLevelResultWhenGateHasNoFindings(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("gate-only.sarif");
        var result = new AIVResult(false, List.of(GateResult.fail("design", "forbidden")));
        SarifReportWriter.write(result, out, "1.0");
        String s = Files.readString(out);
        assertTrue(s.contains("design.gate"));
        assertTrue(s.contains("forbidden"));
    }

    @Test
    void writesCommaBetweenMultipleResults(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("multi.sarif");
        var f1 = Finding.atLine("a", "x.java", 1, "m1");
        var f2 = Finding.atLine("b", "y.java", 2, "m2");
        var result = new AIVResult(false, List.of(GateResult.fail("g", "x", List.of(f1, f2))));
        SarifReportWriter.write(result, out, "1.0");
        String s = Files.readString(out);
        assertTrue(s.contains("\"ruleId\": \"a\""));
        assertTrue(s.contains("\"ruleId\": \"b\""));
    }

    @Test
    void writesSarifVersionAndResult(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out.sarif");
        var f = Finding.atLine("density.entropy", "a/B.java", 4, "low entropy");
        var result = new AIVResult(false, List.of(GateResult.fail("density", "x", List.of(f))));
        SarifReportWriter.write(result, out, "9.9.9");
        String s = Files.readString(out);
        assertTrue(s.contains("\"version\": \"2.1.0\""));
        assertTrue(s.contains("density.entropy"));
        assertTrue(s.contains("a/B.java"));
        assertTrue(s.contains("\"startLine\": 4"));
    }
}
