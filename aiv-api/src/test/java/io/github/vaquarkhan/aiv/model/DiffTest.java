/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class DiffTest {

    @Test
    void gettersReturnValues() {
        var files = List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "content"));
        var diff = new Diff("main", "HEAD", files, "raw diff text");
        assertEquals("main", diff.getBaseRef());
        assertEquals("HEAD", diff.getHeadRef());
        assertEquals(1, diff.getChangedFiles().size());
        assertEquals("raw diff text", diff.getRawDiff());
    }

    @Test
    void nullFilesBecomesEmpty() {
        var diff = new Diff("a", "b", null, null);
        assertTrue(diff.getChangedFiles().isEmpty());
        assertEquals("", diff.getRawDiff());
    }

    @Test
    void extendedConstructor() {
        var diff = new Diff("a", "b", List.of(), "", 10, 5, "dev@example.com", true);
        assertEquals(10, diff.getLinesAdded());
        assertEquals(5, diff.getLinesDeleted());
        assertEquals(5, diff.getNetLoc());
        assertEquals("dev@example.com", diff.getAuthorEmail());
        assertFalse(diff.isHeadCommitSigned());
        assertTrue(diff.isSkipDirectivePresent());
    }

    @Test
    void signedCommitFlag() {
        var diff = new Diff("a", "b", List.of(), "", 0, 0, "dev@example.com", true, false, List.of(), Map.of());
        assertTrue(diff.isHeadCommitSigned());
        assertFalse(diff.isSkipDirectivePresent());
    }

    @Test
    void warningsAndPerFileNetLoc() {
        var diff = new Diff("a", "b", List.of(), "", 0, 0, null, false,
                List.of("w1"), Map.of("x/y.java", -3));
        assertEquals(1, diff.getWarnings().size());
        assertEquals(1, diff.getPerFileNetLoc().size());
        assertEquals(-3, diff.getNetLocForFile("x/y.java"));
        assertNull(diff.getNetLocForFile("missing.java"));
        assertNull(diff.getNetLocForFile(null));
    }
}
