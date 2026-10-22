/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class GateResultTest {

    @Test
    void passFactory() {
        var r = GateResult.pass("density");
        assertTrue(r.isPassed());
        assertEquals("density", r.getGateId());
        assertEquals("OK", r.getMessage());
    }

    @Test
    void failFactory() {
        var r = GateResult.fail("design", "forbidden call");
        assertFalse(r.isPassed());
        assertEquals("design", r.getGateId());
        assertEquals("forbidden call", r.getMessage());
    }

    @Test
    void nullMessageBecomesEmpty() {
        var r = new GateResult("x", false, null);
        assertEquals("", r.getMessage());
        assertTrue(r.blocksCi());
    }

    @Test
    void advisoryDoesNotBlockCi() {
        var r = GateResult.advisory("g", "msg");
        assertFalse(r.isPassed());
        assertFalse(r.blocksCi());
    }

    @Test
    void failWithFindings() {
        var f = Finding.atLine("g.r", "a.java", 2, "x");
        var r = GateResult.fail("g", "summary", java.util.List.of(f));
        assertEquals(1, r.getFindings().size());
        assertEquals(f, r.getFindings().get(0));
    }

    @Test
    void passHasNoFindings() {
        assertTrue(GateResult.pass("g").getFindings().isEmpty());
    }

    @Test
    void fourArgConstructorPassesThrough() {
        var r = new GateResult("g", true, "custom", false);
        assertTrue(r.isPassed());
        assertEquals("custom", r.getMessage());
        assertTrue(r.getFindings().isEmpty());
    }

    @Test
    void advisoryWithFindings() {
        var f = Finding.atLine("x", "a.java", 1, "m");
        var r = GateResult.advisory("g", "sum", java.util.List.of(f));
        assertEquals(1, r.getFindings().size());
    }

    @Test
    void nullFindingsListNormalizesToEmpty() {
        var r = new GateResult("g", false, "m", true, null);
        assertTrue(r.getFindings().isEmpty());
    }

    @Test
    void threeArgConstructorSetsBlocksFromPassed() {
        var failed = new GateResult("g", false, "m");
        assertFalse(failed.isPassed());
        assertTrue(failed.blocksCi());
        var ok = new GateResult("g", true, "m");
        assertTrue(ok.isPassed());
        assertFalse(ok.blocksCi());
    }
}
