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

import org.apache.aiv.model.AIVConfig;
import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.QualityGate;
import org.apache.aiv.util.FileExtensions;

import java.util.Map;
import java.util.Set;

/**
 * Design compliance gate using YAML rules. Checks forbidden/required patterns.
 * Supports multiple languages via configurable file extensions.
 *
 * @author Vaquar Khan
 */
public final class DesignComplianceGate implements QualityGate {

    private static final String RULES_PATH_KEY = "rules_path";

    @Override
    public String getId() {
        return "design";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        String rulesPath = getConfigString(context, RULES_PATH_KEY, ".aiv/design-rules.yaml");
        Set<String> extensions = FileExtensions.fromConfig(getGateConfig(context));
        DesignRules rules = DesignRulesLoader.load(context.getWorkspace().resolve(rulesPath));

        if (rules == null || rules.getConstraints().isEmpty()) {
            return GateResult.pass(getId());
        }

        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            if (!FileExtensions.matches(file.getPath(), extensions)) {
                continue;
            }
            String content = file.getContent();
            String path = file.getPath();
            for (DesignRules.Constraint c : rules.getConstraints()) {
                String contentLower = content.toLowerCase();
                String pathLower = path.toLowerCase();
                boolean applies = c.getKeywords().isEmpty()
                        || c.getKeywords().stream().anyMatch(k ->
                        contentLower.contains(k.toLowerCase()) || pathLower.contains(k.toLowerCase()));
                if (!applies) {
                    continue;
                }
                for (String forbidden : c.getForbiddenCalls()) {
                    if (contentLower.contains(forbidden.toLowerCase())) {
                        return GateResult.fail(getId(),
                                String.format("Forbidden call '%s' in %s (constraint: %s)", forbidden, path, c.getId()));
                    }
                }
                for (String required : c.getRequiredCalls()) {
                    if (!contentLower.contains(required.toLowerCase())) {
                        return GateResult.fail(getId(),
                                String.format("Required call '%s' missing in %s (constraint: %s)", required, path, c.getId()));
                    }
                }
            }
        }
        return GateResult.pass(getId());
    }

    private Map<String, Object> getGateConfig(AIVContext context) {
        return context.getConfig().getGates().stream()
                .filter(g -> getId().equals(g.getId()))
                .findFirst()
                .map(AIVConfig.GateConfig::getConfig)
                .orElse(Map.of());
    }

    private String getConfigString(AIVContext context, String key, String defaultValue) {
        Object v = getGateConfig(context).get(key);
        return v != null ? v.toString() : defaultValue;
    }
}
