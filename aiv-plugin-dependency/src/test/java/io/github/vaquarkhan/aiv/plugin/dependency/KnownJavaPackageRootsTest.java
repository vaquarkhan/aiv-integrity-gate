/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.dependency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnownJavaPackageRootsTest {

    @Test
    void expandPrefixesIncludesArtifactSpecificMappings() {
        var prefixes = KnownJavaPackageRoots.expandPrefixes("com.fasterxml.jackson.core", "jackson-databind");
        assertTrue(prefixes.contains("com.fasterxml.jackson.databind"));
    }
}
