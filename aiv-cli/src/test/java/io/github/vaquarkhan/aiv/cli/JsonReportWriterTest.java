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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReportWriterTest {

    @Test
    void isDoctorRunDetectsDoctorNoticePrefix() {
        assertFalse(JsonReportWriter.isDoctorRun(new AIVResult(true, List.of(), List.of())));
        assertTrue(JsonReportWriter.isDoctorRun(new AIVResult(true, List.of(),
                List.of("[DOCTOR] Informational run"))));
    }

    @Test
    void writesDoctorModeTrueWhenNoticesContainDoctor(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("doctor.json");
        var result = new AIVResult(true, List.of(GateResult.pass("density")),
                List.of("[DOCTOR] Informational run: tuning"));
        JsonReportWriter.write(result, out, "1.0.0");
        assertTrue(Files.readString(out).contains("\"doctor_mode\": true"));
    }

    @Test
    void writesSchemaVersionAndGates(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out.json");
        var result = new AIVResult(true,
                List.of(GateResult.pass("density")),
                List.of("notice one"));
        JsonReportWriter.write(result, out, "9.9.9");
        String s = Files.readString(out);
        assertTrue(s.contains("\"schema_version\": 2"));
        assertTrue(s.contains("\"doctor_mode\": false"));
        assertTrue(s.contains("\"aiv_cli_version\": \"9.9.9\""));
        assertTrue(s.contains("\"id\": \"density\""));
        assertTrue(s.contains("notice one"));
    }

    @Test
    void writesEmptyGatesArray(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("empty.json");
        JsonReportWriter.write(new AIVResult(true, List.of(), List.of()), out, "1.0.0");
        String s = Files.readString(out);
        assertTrue(s.contains("\"gates\": []"));
    }

    @Test
    void writesMultipleGatesAndNotices(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("multi.json");
        var result = new AIVResult(true,
                List.of(GateResult.pass("a"), GateResult.pass("b")),
                List.of("n1", "n2"));
        JsonReportWriter.write(result, out, "1.0.0");
        String s = Files.readString(out);
        assertTrue(s.contains("\"id\": \"a\""));
        assertTrue(s.contains("\"id\": \"b\""));
        assertTrue(s.contains("n1"));
        assertTrue(s.contains("n2"));
    }

    @Test
    void escapeHandlesNullEmptyAndSpecialChars() {
        assertEquals("", JsonReportWriter.escape(null));
        assertEquals("", JsonReportWriter.escape(""));
        assertEquals("a\\\\b", JsonReportWriter.escape("a\\b"));
        assertEquals("say \\\"hi\\\"", JsonReportWriter.escape("say \"hi\""));
        assertEquals("a\\nb", JsonReportWriter.escape("a\nb"));
        assertEquals("a\\rb", JsonReportWriter.escape("a\rb"));
        assertEquals("a\\tb", JsonReportWriter.escape("a\tb"));
        assertEquals("\\u0001x", JsonReportWriter.escape("\u0001x"));
    }

    @Test
    void privateConstructorForCoverage() throws Exception {
        var c = JsonReportWriter.class.getDeclaredConstructor();
        c.setAccessible(true);
        c.newInstance();
    }

    @Test
    void writesFindingsWithOptionalColumnsAndMultipleEntries(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("findings.json");
        var withRange = new Finding("rule", "src\\Main.java", 2, 5, 2, 9, "m\"sg");
        var plain = Finding.atLine("r2", "b.java", 99, "plain");
        var result = new AIVResult(false,
                List.of(GateResult.fail("g", "gate message\nhere", List.of(withRange, plain))),
                List.of());
        JsonReportWriter.write(result, out, "1.0");
        String s = Files.readString(out);
        assertTrue(s.contains("\"start_column\": 5"));
        assertTrue(s.contains("\"end_line\": 2"));
        assertTrue(s.contains("\"end_column\": 9"));
        assertTrue(s.contains("\"src\\\\Main.java\"") || s.contains("Main.java"));
    }
}
