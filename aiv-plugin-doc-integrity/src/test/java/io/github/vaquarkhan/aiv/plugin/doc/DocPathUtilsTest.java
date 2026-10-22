/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DocPathUtilsTest {

    @Test
    void resolveFromWorkspaceRoot(@TempDir Path dir) {
        Path ws = dir.toAbsolutePath().normalize();
        assertEquals(ws.resolve("docs/a.md").normalize(),
                DocPathUtils.resolveFromWorkspaceRoot(dir, "docs/a.md"));
    }

    @Test
    void packagePrivateConstructorForCoverage() {
        assertNotNull(new DocPathUtils());
    }
}
