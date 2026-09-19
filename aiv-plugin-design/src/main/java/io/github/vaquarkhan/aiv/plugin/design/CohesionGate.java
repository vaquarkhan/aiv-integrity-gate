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

package io.github.vaquarkhan.aiv.plugin.design;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Flags pull requests that sprawl across too many independent path areas.
 * Generic design signal for "split this PR" review feedback (e.g. unrelated CI,
 * scripts, and workflows bundled together) — not project-specific.
 *
 * <p>Config keys (under {@code gates[].config} for id {@code cohesion}):
 * <ul>
 *   <li>{@code max_areas} — max distinct path areas before finding (default 3)</li>
 *   <li>{@code area_depth} — directory segments used as an area (default 2)</li>
 *   <li>{@code max_files} — optional hard cap on changed files (0 = disabled)</li>
 * </ul>
 */
public final class CohesionGate implements QualityGate {

    private static final int DEFAULT_MAX_AREAS = 3;
    private static final int DEFAULT_AREA_DEPTH = 2;
    private static final int DEFAULT_MAX_FILES = 0;

    @Override
    public String getId() {
        return "cohesion";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        int maxAreas = getInt(context, "max_areas", DEFAULT_MAX_AREAS);
        int areaDepth = Math.max(1, getInt(context, "area_depth", DEFAULT_AREA_DEPTH));
        int maxFiles = getInt(context, "max_files", DEFAULT_MAX_FILES);

        List<ChangedFile> files = context.getDiff().getChangedFiles();
        List<Finding> findings = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        if (maxFiles > 0 && files.size() > maxFiles) {
            String msg = String.format(
                    "PR changes %d files (max_files=%d); consider splitting for reviewability",
                    files.size(), maxFiles);
            failures.add(msg);
            findings.add(Finding.atLine("cohesion.files", "(diff)", 1, msg));
        }

        Set<String> areas = new TreeSet<>();
        for (ChangedFile f : files) {
            String area = areaOf(f.getPath(), areaDepth);
            if (!area.isBlank()) {
                areas.add(area);
            }
        }

        if (maxAreas > 0 && areas.size() > maxAreas) {
            List<String> sample = new ArrayList<>(areas);
            String shown = String.join(", ", sample.subList(0, Math.min(8, sample.size())));
            if (sample.size() > 8) {
                shown = shown + ", …";
            }
            String msg = String.format(
                    "PR spans %d path areas (max_areas=%d, area_depth=%d): %s — split unrelated concerns",
                    areas.size(), maxAreas, areaDepth, shown);
            failures.add(msg);
            findings.add(Finding.atLine("cohesion.areas", "(diff)", 1, msg));
        }

        if (failures.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("\n", failures), findings);
    }

    /**
     * Path area = up to {@code depth} directory segments (file name excluded).
     * Root-level files map to {@code (root)}.
     */
    static String areaOf(String path, int depth) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String norm = path.replace('\\', '/').replaceAll("^/+", "");
        if (norm.isBlank()) {
            return "";
        }
        String[] parts = norm.split("/");
        if (parts.length <= 1) {
            return "(root)";
        }
        int take = Math.min(depth, parts.length - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < take; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** Package-private for tests: distinct areas for a path list. */
    static Set<String> areasOf(List<String> paths, int depth) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : paths) {
            String a = areaOf(p, depth);
            if (!a.isBlank()) {
                out.add(a);
            }
        }
        return out;
    }

    private Map<String, Object> getGateConfig(AIVContext context) {
        return context.getConfig().getGates().stream()
                .filter(g -> getId().equals(g.getId()))
                .findFirst()
                .map(AIVConfig.GateConfig::getConfig)
                .orElse(Map.of());
    }

    private int getInt(AIVContext context, String key, int defaultValue) {
        Object v = getGateConfig(context).get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
