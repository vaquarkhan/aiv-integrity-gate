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

import org.apache.aiv.model.*;
import org.apache.aiv.port.ConfigProvider;
import org.apache.aiv.port.DiffProvider;
import org.apache.aiv.port.ReportPublisher;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class OrchestratorTest {

    @Test
    void runReturnsZeroWhenAllPass() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(List.of(), java.util.Map.of());
            }
        };
        var results = new ArrayList<AIVResult>();
        var reportPublisher = new ReportPublisher() {
            @Override
            public void publish(AIVResult result) {
                results.add(result);
            }
        };
        var orch = new Orchestrator(diffProvider, configProvider, reportPublisher);
        int code = orch.run(Paths.get("."), "main", "HEAD");
        assertEquals(0, code);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isPassed());
    }

    @Test
    void reportPublisherIsInvoked() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(List.of(), java.util.Map.of());
            }
        };
        var results = new ArrayList<AIVResult>();
        var reportPublisher = new ReportPublisher() {
            @Override
            public void publish(AIVResult result) {
                results.add(result);
            }
        };
        var orch = new Orchestrator(diffProvider, configProvider, reportPublisher);
        orch.run(Paths.get("."), "main", "HEAD");
        assertEquals(1, results.size());
    }
}
