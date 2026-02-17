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

package org.apache.aiv.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Configuration for AIV gates. Loaded from .aiv/config.yaml.
 *
 * @author Vaquar Khan
 */
public final class AIVConfig {

    private final List<GateConfig> gates;
    private final Map<String, Object> globalConfig;

    public AIVConfig(List<GateConfig> gates, Map<String, Object> globalConfig) {
        this.gates = gates == null ? Collections.emptyList() : List.copyOf(gates);
        this.globalConfig = globalConfig == null ? Collections.emptyMap() : Map.copyOf(globalConfig);
    }

    public List<GateConfig> getGates() {
        return gates;
    }

    public Map<String, Object> getGlobalConfig() {
        return globalConfig;
    }

    /**
     * Returns path patterns to exclude from validation (e.g. generated dirs, *.pb.java).
     */
    @SuppressWarnings("unchecked")
    public List<String> getExcludePaths() {
        Object val = globalConfig.get("exclude_paths");
        if (val instanceof List) {
            return ((List<?>) val).stream()
                    .filter(o -> o != null)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    public static final class GateConfig {
        private final String id;
        private final boolean enabled;
        private final Map<String, Object> config;

        public GateConfig(String id, boolean enabled, Map<String, Object> config) {
            this.id = id;
            this.enabled = enabled;
            this.config = config == null ? Collections.emptyMap() : Map.copyOf(config);
        }

        public String getId() {
            return id;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Map<String, Object> getConfig() {
            return config;
        }
    }
}
