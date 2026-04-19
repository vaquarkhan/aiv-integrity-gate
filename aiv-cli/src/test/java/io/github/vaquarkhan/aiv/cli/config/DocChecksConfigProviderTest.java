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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocChecksConfigProviderTest {

    @Test
    void returnsDelegateConfigWhenIncludeDocChecksFalse() {
        var delegate = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(Path workspace) {
                return new AIVConfig(List.of(), Map.of());
            }
        };
        var provider = new DocChecksConfigProvider(delegate, false);
        var config = provider.getConfig(Path.of("."));
        assertTrue(config.getGates().stream().noneMatch(g -> "doc-integrity".equals(g.getId())));
    }

    @Test
    void addsDocIntegrityWhenIncludeDocChecksTrue() {
        var delegate = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(Path workspace) {
                return new AIVConfig(List.of(), Map.of());
            }
        };
        var provider = new DocChecksConfigProvider(delegate, true);
        var config = provider.getConfig(Path.of("."));
        var docGate = config.getGates().stream().filter(g -> "doc-integrity".equals(g.getId())).findFirst();
        assertTrue(docGate.isPresent());
        assertTrue(docGate.get().isEnabled());
        assertFalse(Boolean.TRUE.equals(docGate.get().getConfig().get("auto")));
    }

    @Test
    void enablesExistingDocIntegrityWhenIncludeDocChecksTrue() {
        var delegate = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(Path workspace) {
                return new AIVConfig(
                        List.of(new AIVConfig.GateConfig("doc-integrity", false, Map.of("rules_path", ".aiv/doc-rules.yaml"))),
                        Map.of());
            }
        };
        var provider = new DocChecksConfigProvider(delegate, true);
        var config = provider.getConfig(Path.of("."));
        var docGate = config.getGates().stream().filter(g -> "doc-integrity".equals(g.getId())).findFirst();
        assertTrue(docGate.isPresent());
        assertTrue(docGate.get().isEnabled());
    }
}
