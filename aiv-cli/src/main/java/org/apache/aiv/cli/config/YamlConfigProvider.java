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
import org.apache.aiv.port.ConfigProvider;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Loads config from .aiv/config.yaml.
 *
 * @author Vaquar Khan
 */
public final class YamlConfigProvider implements ConfigProvider {

    @Override
    public AIVConfig getConfig(Path workspace) {
        Path configPath = workspace.resolve(".aiv/config.yaml");
        if (!Files.exists(configPath)) {
            return defaultConfig();
        }
        try {
            String content = Files.readString(configPath);
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(content);
            if (root == null) {
                return defaultConfig();
            }
            List<AIVConfig.GateConfig> gates = parseGates(root);
            @SuppressWarnings("unchecked")
            Map<String, Object> global = (Map<String, Object>) root.getOrDefault("global", Collections.emptyMap());
            return new AIVConfig(gates, global);
        } catch (Exception e) {
            return defaultConfig();
        }
    }

    private AIVConfig defaultConfig() {
        return new AIVConfig(
                List.of(
                        new AIVConfig.GateConfig("density", true, Map.of("ldr_threshold", 0.25, "entropy_threshold", 3.8)),
                        new AIVConfig.GateConfig("design", true, Map.of("rules_path", ".aiv/design-rules.yaml")),
                        new AIVConfig.GateConfig("dependency", true, Map.of()),
                        new AIVConfig.GateConfig("invariant", true, Map.of())
                ),
                Collections.emptyMap()
        );
    }

    @SuppressWarnings("unchecked")
    private List<AIVConfig.GateConfig> parseGates(Map<String, Object> root) {
        List<Map<String, Object>> gatesList = (List<Map<String, Object>>) root.get("gates");
        if (gatesList == null) {
            return defaultConfig().getGates();
        }
        List<AIVConfig.GateConfig> gates = new ArrayList<>();
        for (Map<String, Object> g : gatesList) {
            String id = (String) g.get("id");
            Boolean enabled = (Boolean) g.get("enabled");
            Map<String, Object> config = (Map<String, Object>) g.getOrDefault("config", Collections.emptyMap());
            gates.add(new AIVConfig.GateConfig(id, enabled != null && enabled, config != null ? config : Map.of()));
        }
        return gates.isEmpty() ? defaultConfig().getGates() : gates;
    }
}
