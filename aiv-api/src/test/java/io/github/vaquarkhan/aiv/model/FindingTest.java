/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FindingTest {

    @Test
    void atLineSetsPathAndMessage() {
        Finding f = Finding.atLine("rule", "src/X.java", 3, "oops");
        assertEquals("rule", f.getRuleId());
        assertEquals("src/X.java", f.getFilePath());
        assertEquals(3, f.getStartLine());
        assertEquals("oops", f.getMessage());
    }

    @Test
    void startLineMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Finding.atLine("r", "f", 0, "m"));
    }

    @Test
    void fullConstructorExposesOptionalColumns() {
        Finding f = new Finding("r", "f.java", 2, 3, 4, 5, "msg");
        assertEquals(3, f.getStartColumn());
        assertEquals(4, f.getEndLine());
        assertEquals(5, f.getEndColumn());
    }

    @Test
    void nullMessageBecomesEmpty() {
        assertEquals("", new Finding("r", "f.java", 1, null, null, null, null).getMessage());
    }
}
