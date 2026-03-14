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

package org.apache.aiv.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class AIVConfigTest {

    @Test
    void gateConfigGetters() {
        var gc = new AIVConfig.GateConfig("density", true, Map.of("x", 1));
        assertEquals("density", gc.getId());
        assertTrue(gc.isEnabled());
        assertEquals(1, gc.getConfig().get("x"));
    }

    @Test
    void configGetters() {
        var gc = new AIVConfig.GateConfig("d", true, Map.of());
        var config = new AIVConfig(List.of(gc), Map.of("k", "v"));
        assertEquals(1, config.getGates().size());
        assertEquals("v", config.getGlobalConfig().get("k"));
    }

    @Test
    void nullGatesBecomesEmpty() {
        var config = new AIVConfig(null, null);
        assertTrue(config.getGates().isEmpty());
        assertTrue(config.getGlobalConfig().isEmpty());
    }

    @Test
    void excludePathsParsesListAndFiltersBlanks() {
        var config = new AIVConfig(
                List.of(),
                Map.of("exclude_paths", Arrays.asList("**/generated/**", "", "  ", null, 123))
        );
        List<String> exclude = config.getExcludePaths();
        assertTrue(exclude.contains("**/generated/**"));
        assertTrue(exclude.contains("123"));
        assertFalse(exclude.contains(""));
    }

    @Test
    void excludePathsNonListReturnsEmpty() {
        var config = new AIVConfig(List.of(), Map.of("exclude_paths", "not-a-list"));
        assertTrue(config.getExcludePaths().isEmpty());
    }

    @Test
    void gateConfigNullConfigBecomesEmptyMap() {
        var gc = new AIVConfig.GateConfig("density", true, null);
        assertNotNull(gc.getConfig());
        assertTrue(gc.getConfig().isEmpty());
    }
}
