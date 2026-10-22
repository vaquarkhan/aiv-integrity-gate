/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.design;

import org.junit.jupiter.api.Test;

import java.util.List;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class DesignRulesLoaderTest {

    @Test
    void canInstantiateForCoverage() {
        assertNotNull(new DesignRulesLoader());
    }

    @Test
    void loadMissingFileReturnsEmpty() {
        var rules = DesignRulesLoader.load(Path.of("/nonexistent/path"));
        assertNotNull(rules);
        assertTrue(rules.getConstraints().isEmpty());
    }

    @Test
    void loadValidYaml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rules.yaml"), """
            constraints:
              - id: c1
                keywords: [a, b]
                forbidden_calls: [f]
                required_calls: [r]
            """);
        var rules = DesignRulesLoader.load(dir.resolve("rules.yaml"));
        assertEquals(1, rules.getConstraints().size());
        assertEquals("c1", rules.getConstraints().get(0).getId());
        assertEquals(List.of("a", "b"), rules.getConstraints().get(0).getKeywords());
    }

    @Test
    void loadEmptyFileReturnsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("empty.yaml"), "");
        var rules = DesignRulesLoader.load(dir.resolve("empty.yaml"));
        assertTrue(rules.getConstraints().isEmpty());
    }

    @Test
    void loadYamlWithoutConstraintsReturnsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("no-constraints.yaml"), "foo: bar\n");
        var rules = DesignRulesLoader.load(dir.resolve("no-constraints.yaml"));
        assertNotNull(rules);
        assertTrue(rules.getConstraints().isEmpty());
    }

    @Test
    void loadInvalidYamlFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("invalid.yaml"), "constraints: [\n");
        assertThrows(IllegalArgumentException.class,
                () -> DesignRulesLoader.load(dir.resolve("invalid.yaml")));
    }

    @Test
    void loadYamlWithWrongTypesFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("wrong-types.yaml"), "constraints: 123\n");
        assertThrows(IllegalArgumentException.class,
                () -> DesignRulesLoader.load(dir.resolve("wrong-types.yaml")));
    }
}
