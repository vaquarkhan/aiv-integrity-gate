/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocFileExtensionsTest {

    @Test
    void matchesMd() {
        assertTrue(DocFileExtensions.matches("README.md"));
        assertTrue(DocFileExtensions.matches("docs/guide.md"));
        assertTrue(DocFileExtensions.matches("a/b/c.MD"));
    }

    @Test
    void matchesTxt() {
        assertTrue(DocFileExtensions.matches("notes.txt"));
        assertTrue(DocFileExtensions.matches("a.TXT"));
    }

    @Test
    void matchesRst() {
        assertTrue(DocFileExtensions.matches("index.rst"));
        assertTrue(DocFileExtensions.matches("docs/readme.RST"));
    }

    @Test
    void matchesSpecialNames() {
        assertTrue(DocFileExtensions.matches("AGENTS.md"));
        assertTrue(DocFileExtensions.matches("CLAUDE.md"));
        assertTrue(DocFileExtensions.matches("CONTRIBUTING.md"));
        assertTrue(DocFileExtensions.matches("README.md"));
        assertTrue(DocFileExtensions.matches("docs/AGENTS.md"));
    }

    @Test
    void rejectsNonDoc() {
        assertFalse(DocFileExtensions.matches("Foo.java"));
        assertFalse(DocFileExtensions.matches("main.py"));
        assertFalse(DocFileExtensions.matches("script.sh"));
        assertFalse(DocFileExtensions.matches("config.yaml"));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertFalse(DocFileExtensions.matches(null));
        assertFalse(DocFileExtensions.matches(""));
    }
}
