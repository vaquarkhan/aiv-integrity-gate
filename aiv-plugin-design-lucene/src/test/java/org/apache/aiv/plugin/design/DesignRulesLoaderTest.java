/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.aiv.plugin.design;

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
    void loadInvalidYamlReturnsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("invalid.yaml"), "constraints: [\n");
        var rules = DesignRulesLoader.load(dir.resolve("invalid.yaml"));
        assertTrue(rules.getConstraints().isEmpty());
    }

    @Test
    void loadYamlWithWrongTypesReturnsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("wrong-types.yaml"), "constraints: 123\n");
        var rules = DesignRulesLoader.load(dir.resolve("wrong-types.yaml"));
        assertTrue(rules.getConstraints().isEmpty());
    }
}
