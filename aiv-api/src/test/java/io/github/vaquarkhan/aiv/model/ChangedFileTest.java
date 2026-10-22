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
class ChangedFileTest {

    @Test
    void gettersReturnValues() {
        var f = new ChangedFile("p.java", ChangedFile.ChangeType.MODIFIED, "code");
        assertEquals("p.java", f.getPath());
        assertEquals(ChangedFile.ChangeType.MODIFIED, f.getChangeType());
        assertEquals("code", f.getContent());
    }

    @Test
    void nullContentBecomesEmpty() {
        var f = new ChangedFile("x", ChangedFile.ChangeType.ADDED, null);
        assertEquals("", f.getContent());
    }
}
