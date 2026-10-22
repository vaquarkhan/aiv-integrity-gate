/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.design;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class DesignRulesTest {

    @Test
    void constraintGetters() {
        var c = new DesignRules.Constraint("id", List.of("k"), List.of("f"), List.of("r"));
        assertEquals("id", c.getId());
        assertEquals(1, c.getKeywords().size());
        assertEquals(1, c.getForbiddenCalls().size());
        assertEquals(1, c.getRequiredCalls().size());
    }

    @Test
    void nullListsBecomeEmpty() {
        var c = new DesignRules.Constraint("x", null, null, null);
        assertTrue(c.getKeywords().isEmpty());
        assertTrue(c.getForbiddenCalls().isEmpty());
        assertTrue(c.getRequiredCalls().isEmpty());
    }

    @Test
    void rulesGetters() {
        var c = new DesignRules.Constraint("x", List.of(), List.of(), List.of());
        var r = new DesignRules(List.of(c));
        assertEquals(1, r.getConstraints().size());
    }

    @Test
    void nullConstraintsBecomeEmpty() {
        var r = new DesignRules(null);
        assertNotNull(r.getConstraints());
        assertTrue(r.getConstraints().isEmpty());
    }
}
