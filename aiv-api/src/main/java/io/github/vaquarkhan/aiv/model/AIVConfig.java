/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public Optional<GateConfig> findGate(String id) {
        return gates.stream().filter(g -> g.getId().equals(id)).findFirst();
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

    /**
     * When non-empty, only these author emails may use {@code /aiv skip} (case-insensitive match).
     */
    @SuppressWarnings("unchecked")
    public List<String> getSkipAllowlist() {
        Object val = globalConfig.get("skip_allowlist");
        if (val instanceof List) {
            return ((List<?>) val).stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * When true, stop at the first failing gate. Default is false (run all gates and report every failure).
     */
    public boolean isFailFast() {
        Object v = globalConfig.get("fail_fast");
        return v instanceof Boolean && (Boolean) v;
    }

    public static final class GateConfig {
        private final String id;
        private final boolean enabled;
        private final Map<String, Object> config;
        /** {@code fail} (default) fails CI when the gate fails; {@code warn} reports only (advisory). */
        private final String severity;

        public GateConfig(String id, boolean enabled, Map<String, Object> config) {
            this(id, enabled, config, "fail");
        }

        public GateConfig(String id, boolean enabled, Map<String, Object> config, String severity) {
            this.id = id;
            this.enabled = enabled;
            this.config = config == null ? Collections.emptyMap() : Map.copyOf(config);
            this.severity = normalizeSeverity(severity);
        }

        private static String normalizeSeverity(String s) {
            if (s == null || s.isBlank()) {
                return "fail";
            }
            return s.trim().toLowerCase();
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

        /** {@code fail} or {@code warn}. */
        public String getSeverity() {
            return severity;
        }
    }
}
