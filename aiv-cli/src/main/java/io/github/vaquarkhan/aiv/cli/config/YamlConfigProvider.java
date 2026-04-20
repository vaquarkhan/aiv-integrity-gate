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

package io.github.vaquarkhan.aiv.cli.config;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads config from .aiv/config.yaml.
 *
 * @author Vaquar Khan
 */
public final class YamlConfigProvider implements ConfigProvider {
    static final int CONFIG_SCHEMA_VERSION = 1;

    @Override
    public AIVConfig getConfig(Path workspace) {
        Path configPath = workspace.resolve(".aiv/config.yaml");
        if (!Files.exists(configPath)) {
            return defaultConfig();
        }
        try {
            String content = Files.readString(configPath);
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> root = yaml.load(content);
            if (root == null) {
                return defaultConfig();
            }
            List<AIVConfig.GateConfig> gates = parseGates(root);
            @SuppressWarnings("unchecked")
            Map<String, Object> global = new HashMap<>(
                    (Map<String, Object>) root.getOrDefault("global", Collections.emptyMap()));
            Integer schemaVersion = parseSchemaVersion(root.get("schema_version"));
            global.put("schema_version", schemaVersion);
            if (root.containsKey("exclude_paths") && !global.containsKey("exclude_paths")) {
                global.put("exclude_paths", root.get("exclude_paths"));
            }
            if (root.containsKey("fail_fast") && !global.containsKey("fail_fast")) {
                global.put("fail_fast", root.get("fail_fast"));
            }
            return new AIVConfig(gates, Collections.unmodifiableMap(global));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid config at .aiv/config.yaml: " + e.getMessage(), e);
        }
    }

    private AIVConfig defaultConfig() {
        return new AIVConfig(
                List.of(
                        new AIVConfig.GateConfig("density", true, Map.of(
                                "ldr_threshold", 0.25,
                                "entropy_threshold", 4.0,
                                "refactor_net_loc_threshold", -50)),
                        new AIVConfig.GateConfig("design", true, Map.of("rules_path", ".aiv/design-rules.yaml")),
                        new AIVConfig.GateConfig("dependency", true, Map.of()),
                        new AIVConfig.GateConfig("invariant", false, Map.of()),
                        new AIVConfig.GateConfig("doc-integrity", false, Map.of("rules_path", ".aiv/doc-rules.yaml", "auto", true))
                ),
                Map.of("fail_fast", false, "schema_version", CONFIG_SCHEMA_VERSION)
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
            String severity = (String) g.get("severity");
            gates.add(new AIVConfig.GateConfig(id, enabled != null && enabled,
                    config != null ? config : Map.of(), severity));
        }
        return gates.isEmpty() ? defaultConfig().getGates() : gates;
    }

    private Integer parseSchemaVersion(Object raw) {
        if (raw == null) {
            return CONFIG_SCHEMA_VERSION;
        }
        if (!(raw instanceof Number n)) {
            throw new IllegalArgumentException("schema_version must be a number");
        }
        int parsed = n.intValue();
        if (parsed <= 0) {
            throw new IllegalArgumentException("schema_version must be >= 1");
        }
        if (parsed > CONFIG_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported schema_version " + parsed + " (max supported " + CONFIG_SCHEMA_VERSION + ")");
        }
        return parsed;
    }
}
