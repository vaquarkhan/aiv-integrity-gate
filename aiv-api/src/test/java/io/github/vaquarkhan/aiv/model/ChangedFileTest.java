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
    void structuralMetadataAndAuthorityAreExposed() {
        var f = new ChangedFile("new/path", ChangedFile.ChangeType.RENAMED, "code",
                "old/path", "100644", "160000", "a", "b",
                ChangedFile.ObjectType.GITLINK, ChangedFile.SourceCompleteness.FRAGMENT,
                ChangedFile.Provenance.TRUSTED_GIT_ADAPTER);
        assertEquals("old/path", f.getOldPath());
        assertEquals("100644", f.getOldMode());
        assertEquals("160000", f.getNewMode());
        assertEquals("a", f.getOldObjectId());
        assertEquals("b", f.getNewObjectId());
        assertEquals(ChangedFile.ObjectType.GITLINK, f.getObjectType());
        assertEquals(ChangedFile.SourceCompleteness.FRAGMENT, f.getSourceCompleteness());
        assertEquals(ChangedFile.Provenance.TRUSTED_GIT_ADAPTER, f.getProvenance());
        assertTrue(f.hasAuthoritativeGitStructure());
        assertTrue(f.isNewGitlink());
    }

    @Test
    void structuralDefaultsAndNonIntroductionAreSafe() {
        var defaults = new ChangedFile("x", ChangedFile.ChangeType.ADDED, "", null,
                null, null, null, null, null, null, null);
        assertEquals(ChangedFile.ObjectType.UNKNOWN, defaults.getObjectType());
        assertEquals(ChangedFile.SourceCompleteness.COMPLETE, defaults.getSourceCompleteness());
        assertEquals(ChangedFile.Provenance.CALLER_ASSERTED, defaults.getProvenance());
        assertFalse(defaults.hasAuthoritativeGitStructure());
        assertFalse(defaults.isNewGitlink());

        var update = new ChangedFile("sub", ChangedFile.ChangeType.MODIFIED, "", "sub",
                "160000", "160000", "a", "b", ChangedFile.ObjectType.GITLINK,
                ChangedFile.SourceCompleteness.COMPLETE, ChangedFile.Provenance.TRUSTED_GIT_ADAPTER);
        assertFalse(update.isNewGitlink());
    }

    @Test
    void nullContentBecomesEmpty() {
        var f = new ChangedFile("x", ChangedFile.ChangeType.ADDED, null);
        assertEquals("", f.getContent());
    }
}
