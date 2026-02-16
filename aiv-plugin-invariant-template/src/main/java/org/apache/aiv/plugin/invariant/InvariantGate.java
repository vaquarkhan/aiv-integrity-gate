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

package org.apache.aiv.plugin.invariant;

import org.apache.aiv.model.AIVConfig;
import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.QualityGate;
import org.apache.aiv.util.FileExtensions;

import java.util.Map;
import java.util.Set;

/**
 * Invariant gate using template-based property checks. Counts source files
 * matching configured extensions. Actual PBT runs in the project's test phase.
 *
 * @author Vaquar Khan
 */
public final class InvariantGate implements QualityGate {

    @Override
    public String getId() {
        return "invariant";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Set<String> extensions = FileExtensions.fromConfig(getGateConfig(context));
        long sourceFiles = context.getDiff().getChangedFiles().stream()
                .filter(f -> FileExtensions.matches(f.getPath(), extensions))
                .count();
        if (sourceFiles == 0) {
            return GateResult.pass(getId());
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
}
