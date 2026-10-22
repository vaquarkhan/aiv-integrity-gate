/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/** Immutable context passed to quality gates, including a frozen policy workspace. */
public final class AIVContext {

    private final Path workspace;
    private final Path policyWorkspace;
    private final Diff diff;
    private final AIVConfig config;
    private final Map<String, Object> metadata;

    public AIVContext(Path workspace, Diff diff, AIVConfig config) {
        this(workspace, workspace, diff, config, Collections.emptyMap());
    }

    public AIVContext(Path workspace, Diff diff, AIVConfig config, Map<String, Object> metadata) {
        this(workspace, workspace, diff, config, metadata);
    }

    public AIVContext(Path workspace, Path policyWorkspace, Diff diff, AIVConfig config,
                      Map<String, Object> metadata) {
        this.workspace = workspace;
        this.policyWorkspace = policyWorkspace == null ? workspace : policyWorkspace;
        this.diff = diff;
        this.config = config;
        this.metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }

    public Path getWorkspace() {
        return workspace;
    }

    /** Root containing the immutable `.aiv` policy snapshot consumed by policy-bearing gates. */
    public Path getPolicyWorkspace() {
        return policyWorkspace;
    }

    public Diff getDiff() {
        return diff;
    }

    public AIVConfig getConfig() {
        return config;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public AIVContext withMetadata(String key, Object value) {
        var merged = new java.util.HashMap<>(metadata);
        merged.put(key, value);
        return new AIVContext(workspace, policyWorkspace, diff, config, merged);
    }
}
