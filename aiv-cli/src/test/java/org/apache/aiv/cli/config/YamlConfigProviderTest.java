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

package org.apache.aiv.cli.config;

import org.apache.aiv.model.AIVConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class YamlConfigProviderTest {

    @Test
    void getConfigWhenFileMissingReturnsDefault() {
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(Path.of("/nonexistent"));
        assertNotNull(config);
        assertFalse(config.getGates().isEmpty());
    }

    @Test
    void getConfigWhenFileIsEmptyReturnsDefault(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), "");
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(dir);
        assertFalse(config.getGates().isEmpty());
    }

    @Test
    void missingGatesKeyReturnsDefaultGates(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), "global: {}\n");
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(dir);
        assertFalse(config.getGates().isEmpty());
    }

    @Test
    void getConfigLoadsYaml(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), """
            gates:
              - id: density
                enabled: false
                config:
                  ldr_threshold: 0.3
            """);
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(dir);
        assertEquals(1, config.getGates().size());
        assertFalse(config.getGates().get(0).isEnabled());
        Object ldr = config.getGates().get(0).getConfig().get("ldr_threshold");
        assertNotNull(ldr);
        assertEquals(0.3, ((Number) ldr).doubleValue(), 0.01);
    }

    @Test
    void mergesTopLevelExcludePathsIntoGlobal(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), """
            exclude_paths: ["**/generated/**"]
            gates: []
            """);
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(dir);
        assertTrue(config.getExcludePaths().contains("**/generated/**"));
    }

    @Test
    void globalExcludePathsTakesPrecedenceOverTopLevel(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), """
            exclude_paths: ["**/generated/**"]
            global:
              exclude_paths: ["**/keep/**"]
            gates: []
            """);
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(dir);
        assertFalse(config.getExcludePaths().contains("**/generated/**"));
        assertTrue(config.getExcludePaths().contains("**/keep/**"));
    }

    @Test
    void emptyGatesListReturnsDefaults(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), "gates: []\n");
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(dir);
        assertTrue(config.getGates().size() >= 1);
    }

    @Test
    void gateEnabledDefaultsToFalseWhenMissingAndNullConfigBecomesEmpty(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), """
            gates:
              - id: density
                config: null
            """);
        var provider = new YamlConfigProvider();
        var config = provider.getConfig(dir);
        assertEquals(1, config.getGates().size());
        assertFalse(config.getGates().get(0).isEnabled());
        assertNotNull(config.getGates().get(0).getConfig());
        assertTrue(config.getGates().get(0).getConfig().isEmpty());
    }

    @Test
    void wrongGlobalTypeThrows(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), """
            global: 123
            gates: []
            """);
        var provider = new YamlConfigProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.getConfig(dir));
    }

    @Test
    void getConfigWithMalformedYamlThrows(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/config.yaml"), "gates: [unclosed");
        var provider = new YamlConfigProvider();
        assertThrows(IllegalArgumentException.class, () -> provider.getConfig(dir));
    }
}
