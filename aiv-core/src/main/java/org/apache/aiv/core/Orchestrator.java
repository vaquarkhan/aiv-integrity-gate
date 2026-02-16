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

package org.apache.aiv.core;

import org.apache.aiv.model.AIVConfig;
import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.AIVResult;
import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.Diff;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.ConfigProvider;
import org.apache.aiv.port.DiffProvider;
import org.apache.aiv.port.QualityGate;
import org.apache.aiv.port.ReportPublisher;
import org.apache.aiv.util.PathFilter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Runs the pipeline: diff → config → gates (in order) → publish. Short-circuits on first failure.
 *
 * @author Vaquar Khan
 */
public final class Orchestrator {

    private final DiffProvider diffProvider;
    private final ConfigProvider configProvider;
    private final ReportPublisher reportPublisher;

    public Orchestrator(DiffProvider diffProvider, ConfigProvider configProvider, ReportPublisher reportPublisher) {
        this.diffProvider = diffProvider;
        this.configProvider = configProvider;
        this.reportPublisher = reportPublisher;
    }

    /**
     * Run all enabled gates. Returns exit code: 0 = pass, 1 = fail.
     * Skips all gates when commit message contains "/aiv skip".
     */
    public int run(Path workspace, String baseRef, String headRef) {
        Diff diff = diffProvider.getDiff(workspace, baseRef, headRef);
        if (diff.isSkipRequested()) {
            AIVResult aivResult = new AIVResult(true, List.of(new GateResult("skip", true, "Human override: /aiv skip in commit message")));
            reportPublisher.publish(aivResult);
            return 0;
        }
        AIVConfig config = configProvider.getConfig(workspace);
        List<ChangedFile> filtered = diff.getChangedFiles().stream()
                .filter(f -> !PathFilter.isExcluded(f.getPath(), config.getExcludePaths()))
                .collect(Collectors.toList());
        Diff filteredDiff = new Diff(diff.getBaseRef(), diff.getHeadRef(), filtered, diff.getRawDiff(),
                diff.getLinesAdded(), diff.getLinesDeleted(), diff.getAuthorEmail(), diff.isSkipRequested());
        AIVContext context = new AIVContext(workspace, filteredDiff, config);

        List<QualityGate> gates = loadGates();
        List<GateResult> results = new ArrayList<>();
        boolean overallPassed = true;

        for (QualityGate gate : gates) {
            if (!isGateEnabled(gate.getId(), config)) {
                continue;
            }
            GateResult result = gate.evaluate(context);
            results.add(result);
            if (!result.isPassed()) {
                overallPassed = false;
                break;
            }
        }

        AIVResult aivResult = new AIVResult(overallPassed, results);
        reportPublisher.publish(aivResult);
        return overallPassed ? 0 : 1;
    }

    private List<QualityGate> loadGates() {
        return ServiceLoader.load(QualityGate.class).stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toList());
    }

    private boolean isGateEnabled(String gateId, AIVConfig config) {
        return config.getGates().stream()
                .filter(g -> g.getId().equals(gateId))
                .findFirst()
                .map(AIVConfig.GateConfig::isEnabled)
                .orElse(true);
    }
}
