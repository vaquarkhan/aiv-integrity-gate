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

import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.QualityGate;

import java.util.List;
import java.util.Map;

/**
 * Design compliance gate using Lucene BM25 + YAML rules. Checks forbidden/required patterns.
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
        DesignRules rules = DesignRulesLoader.load(context.getWorkspace().resolve(rulesPath));

        if (rules == null || rules.getConstraints().isEmpty()) {
            return GateResult.pass(getId());
        }

        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            if (!file.getPath().endsWith(".java")) {
                continue;
            }
            String content = file.getContent();
            String path = file.getPath();
            for (DesignRules.Constraint c : rules.getConstraints()) {
                boolean applies = c.getKeywords().isEmpty()
                        || c.getKeywords().stream().anyMatch(k -> content.contains(k) || path.contains(k));
                if (!applies) {
                    continue;
                }
                for (String forbidden : c.getForbiddenCalls()) {
                    if (content.contains(forbidden)) {
                        return GateResult.fail(getId(),
                                String.format("Forbidden call '%s' in %s (constraint: %s)", forbidden, path, c.getId()));
                    }
                }
                for (String required : c.getRequiredCalls()) {
                    if (!content.contains(required)) {
                        return GateResult.fail(getId(),
                                String.format("Required call '%s' missing in %s (constraint: %s)", required, path, c.getId()));
                    }
                }
            }
        }
        return GateResult.pass(getId());
    }

    private String getConfigString(AIVContext context, String key, String defaultValue) {
        return context.getConfig().getGates().stream()
                .filter(g -> getId().equals(g.getId()))
                .findFirst()
                .map(g -> {
                    Object v = g.getConfig().get(key);
                    return v != null ? v.toString() : defaultValue;
                })
                .orElse(defaultValue);
    }
}
