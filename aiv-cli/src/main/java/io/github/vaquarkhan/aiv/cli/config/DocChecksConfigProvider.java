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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a ConfigProvider and enables doc-integrity gate when includeDocChecks is true.
 *
 * @author Vaquar Khan
 */
public final class DocChecksConfigProvider implements ConfigProvider {

    private final ConfigProvider delegate;
    private final boolean includeDocChecks;

    public DocChecksConfigProvider(ConfigProvider delegate, boolean includeDocChecks) {
        this.delegate = delegate;
        this.includeDocChecks = includeDocChecks;
    }

    @Override
    public AIVConfig getConfig(Path workspace) {
        AIVConfig config = delegate.getConfig(workspace);
        if (!includeDocChecks) return config;

        List<AIVConfig.GateConfig> gates = new ArrayList<>(config.getGates());
        boolean found = false;
        for (int i = 0; i < gates.size(); i++) {
            if ("doc-integrity".equals(gates.get(i).getId())) {
                AIVConfig.GateConfig prev = gates.get(i);
                Map<String, Object> cfg = new HashMap<>(prev.getConfig());
                cfg.put("auto", false);
                gates.set(i, new AIVConfig.GateConfig("doc-integrity", true, cfg, prev.getSeverity()));
                found = true;
                break;
            }
        }
        if (!found) {
            gates.add(new AIVConfig.GateConfig("doc-integrity", true,
                    Map.of("rules_path", ".aiv/doc-rules.yaml", "auto", false), "fail"));
        }
        return new AIVConfig(gates, config.getGlobalConfig());
    }
}
