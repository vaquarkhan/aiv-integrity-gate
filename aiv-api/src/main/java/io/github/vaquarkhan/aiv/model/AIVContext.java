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

package io.github.vaquarkhan.aiv.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable context passed to all quality gates. Holds workspace path, diff info, and config.
 *
 * @author Vaquar Khan
 */
public final class AIVContext {

    private final Path workspace;
    private final Diff diff;
    private final AIVConfig config;
    private final Map<String, Object> metadata;

    public AIVContext(Path workspace, Diff diff, AIVConfig config) {
        this(workspace, diff, config, Collections.emptyMap());
    }

    public AIVContext(Path workspace, Diff diff, AIVConfig config, Map<String, Object> metadata) {
        this.workspace = workspace;
        this.diff = diff;
        this.config = config;
        this.metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }

    public Path getWorkspace() {
        return workspace;
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
        return new AIVContext(workspace, diff, config, merged);
    }
}
