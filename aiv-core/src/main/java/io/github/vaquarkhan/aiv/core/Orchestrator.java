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

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.port.ReportPublisher;
import io.github.vaquarkhan.aiv.util.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Runs the pipeline: diff - config - gates (in order) - publish.
 *
 * @author Vaquar Khan
 */
public final class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);
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
     */
    public int run(Path workspace, String baseRef, String headRef) {
        return run(workspace, baseRef, headRef, false);
    }

    /**
     * @param doctor when true, always returns 0 if the diff was computed (informational / tuning run).
     */
    public int run(Path workspace, String baseRef, String headRef, boolean doctor) {
        Diff diff = diffProvider.getDiff(workspace, baseRef, headRef);
        AIVConfig config = configProvider.getConfig(workspace);

        if (shouldHonorSkip(diff, config)) {
            log.warn("Skipping gates: /aiv skip on latest commit (author allowlist satisfied)");
            AIVResult aivResult = new AIVResult(true, List.of(
                    new GateResult("skip", true, "Human override: /aiv skip on latest commit")), diff.getWarnings());
            reportPublisher.publish(aivResult);
            return 0;
        }

        List<ChangedFile> filtered = diff.getChangedFiles().stream()
                .filter(f -> !PathFilter.isExcluded(f.getPath(), config.getExcludePaths()))
                .collect(Collectors.toList());
        if (filtered.size() < diff.getChangedFiles().size()) {
            log.debug("Excluded {} paths from validation", diff.getChangedFiles().size() - filtered.size());
        }
        Map<String, Integer> filteredLoc = new HashMap<>();
        for (ChangedFile f : filtered) {
            Integer v = diff.getNetLocForFile(f.getPath());
            if (v != null) {
                filteredLoc.put(f.getPath().replace("\\", "/"), v);
            }
        }
        Diff filteredDiff = new Diff(diff.getBaseRef(), diff.getHeadRef(), filtered, diff.getRawDiff(),
                diff.getLinesAdded(), diff.getLinesDeleted(), diff.getAuthorEmail(), diff.isSkipDirectivePresent(),
                diff.getWarnings(), filteredLoc);
        AIVContext context = new AIVContext(workspace, filteredDiff, config);

        List<QualityGate> gates = loadGates();
        List<GateResult> results = new ArrayList<>();
        boolean overallPassed = true;
        boolean failFast = config.isFailFast();

        for (QualityGate gate : gates) {
            if (!isGateEnabled(gate.getId(), config, filtered)) {
                continue;
            }
            GateResult raw = gate.evaluate(context);
            GateResult result = applySeverity(raw, gate.getId(), config);
            results.add(result);
            if (!result.isPassed()) {
                log.info("Gate {} failed: {}", gate.getId(), result.getMessage());
                if (result.blocksCi()) {
                    overallPassed = false;
                    if (failFast) {
                        break;
                    }
                } else {
                    log.warn("Advisory (severity=warn): {} — {}", gate.getId(), result.getMessage());
                }
            }
        }

        AIVResult aivResult = new AIVResult(overallPassed, results, filteredDiff.getWarnings());
        reportPublisher.publish(aivResult);
        if (doctor) {
            log.info("Doctor mode: informational exit 0");
            return 0;
        }
        return overallPassed ? 0 : 1;
    }

    static GateResult applySeverity(GateResult raw, String gateId, AIVConfig config) {
        if (raw.isPassed() || !raw.blocksCi()) {
            return raw;
        }
        String sev = config.findGate(gateId).map(AIVConfig.GateConfig::getSeverity).orElse("fail");
        if (!"warn".equals(sev)) {
            return raw;
        }
        return GateResult.advisory(gateId, raw.getMessage());
    }

    private static boolean shouldHonorSkip(Diff diff, AIVConfig config) {
        if (!diff.isSkipDirectivePresent()) {
            return false;
        }
        List<String> allow = config.getSkipAllowlist();
        if (allow.isEmpty()) {
            return true;
        }
        String author = diff.getAuthorEmail();
        if (author == null || author.isBlank()) {
            return false;
        }
        return allow.stream().anyMatch(a -> a.equalsIgnoreCase(author.trim()));
    }

    private List<QualityGate> loadGates() {
        return ServiceLoader.load(QualityGate.class).stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toList());
    }

    private boolean isGateEnabled(String gateId, AIVConfig config, List<ChangedFile> filteredFiles) {
        boolean enabled = config.getGates().stream()
                .filter(g -> g.getId().equals(gateId))
                .findFirst()
                .map(AIVConfig.GateConfig::isEnabled)
                .orElse(!"doc-integrity".equals(gateId));
        if (!enabled) return false;
        if (!"doc-integrity".equals(gateId)) return true;
        Object auto = config.getGates().stream()
                .filter(g -> "doc-integrity".equals(g.getId()))
                .findFirst()
                .map(AIVConfig.GateConfig::getConfig)
                .orElse(Map.of())
                .get("auto");
        if (!Boolean.TRUE.equals(auto)) return true;
        return filteredFiles.stream().anyMatch(f -> isDocPath(f.getPath()));
    }

    private static boolean isDocPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase().replace("\\", "/");
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".rst")) return true;
        String base = lower.contains("/") ? lower.substring(lower.lastIndexOf("/") + 1) : lower;
        return "agents.md".equals(base) || "claude.md".equals(base)
                || "contributing.md".equals(base) || "readme.md".equals(base);
    }
}
