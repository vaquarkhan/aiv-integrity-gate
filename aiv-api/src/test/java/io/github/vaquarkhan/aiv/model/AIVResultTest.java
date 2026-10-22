/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class AIVResultTest {

    @Test
    void getters() {
        var results = List.of(GateResult.pass("density"));
        var r = new AIVResult(true, results);
        assertTrue(r.isPassed());
        assertEquals(1, r.getGateResults().size());
    }

    @Test
    void nullResultsBecomesEmpty() {
        var r = new AIVResult(false, null);
        assertTrue(r.getGateResults().isEmpty());
    }

    @Test
    void noticesGetter() {
        var r = new AIVResult(true, List.of(), List.of("warn1"));
        assertEquals(1, r.getNotices().size());
        assertEquals("warn1", r.getNotices().get(0));
    }

    @Test
    void noticesNullBecomesEmpty() {
        assertTrue(new AIVResult(true, List.of(), null).getNotices().isEmpty());
    }

    @Test
    void hasAdvisoryFailuresDetectsWarnOnly() {
        assertFalse(new AIVResult(true, List.of(GateResult.pass("g"))).hasAdvisoryFailures());
        assertFalse(new AIVResult(false, List.of(GateResult.fail("g", "x"))).hasAdvisoryFailures());
        assertTrue(new AIVResult(true, List.of(GateResult.advisory("design", "soft"))).hasAdvisoryFailures());
    }
}
