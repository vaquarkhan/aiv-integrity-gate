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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    @Test
    void skipAllowlistParsesList() {
        var config = new AIVConfig(List.of(), Map.of("skip_allowlist", List.of("a@b.com", " ")));
        assertEquals(1, config.getSkipAllowlist().size());
        assertEquals("a@b.com", config.getSkipAllowlist().get(0));
    }

    @Test
    void skipAllowlistNonListReturnsEmpty() {
        var config = new AIVConfig(List.of(), Map.of("skip_allowlist", "x"));
        assertTrue(config.getSkipAllowlist().isEmpty());
    }

    @Test
    void failFastReadsBoolean() {
        assertFalse(new AIVConfig(List.of(), Map.of()).isFailFast());
        assertTrue(new AIVConfig(List.of(), Map.of("fail_fast", true)).isFailFast());
        assertFalse(new AIVConfig(List.of(), Map.of("fail_fast", "yes")).isFailFast());
    }

    @Test
    void skipAllowlistFiltersNonStrings() {
        var raw = new ArrayList<>();
        raw.add("a@b.com");
        raw.add(123);
        var config = new AIVConfig(List.of(), Map.of("skip_allowlist", raw));
        assertEquals(1, config.getSkipAllowlist().size());
    }

    @Test
    void gateSeverityDefaultsToFail() {
        var gc = new AIVConfig.GateConfig("density", true, Map.of());
        assertEquals("fail", gc.getSeverity());
    }

    @Test
    void findGateReturnsOptional() {
        var gc = new AIVConfig.GateConfig("density", true, Map.of(), "warn");
        var config = new AIVConfig(List.of(gc), Map.of());
        assertTrue(config.findGate("density").isPresent());
        assertEquals("warn", config.findGate("density").orElseThrow().getSeverity());
        assertTrue(config.findGate("missing").isEmpty());
    }

    @Test
    void severityNormalizedToLowercase() {
        var gc = new AIVConfig.GateConfig("d", true, Map.of(), "WARN");
        assertEquals("warn", gc.getSeverity());
    }

    @Test
    void blankSeverityDefaultsToFail() {
        var gc = new AIVConfig.GateConfig("d", true, Map.of(), "  ");
        assertEquals("fail", gc.getSeverity());
    }

    @Test
    void schemaVersionDefaultsAndReadsNumber() {
        assertEquals(1, new AIVConfig(List.of(), Map.of()).getSchemaVersion());
        assertEquals(3, new AIVConfig(List.of(), Map.of("schema_version", 3)).getSchemaVersion());
    }

    @Test
    void usesTrustedAuthorsBypassWhenDensityListsAuthors() {
        var cfg = new AIVConfig(List.of(
                new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", List.of("a@b.com")), "fail")
        ), Map.of());
        assertTrue(cfg.usesTrustedAuthorsBypass());
    }

    @Test
    void usesTrustedAuthorsBypassFalseWhenDensityDisabledOrEmpty() {
        assertFalse(new AIVConfig(List.of(
                new AIVConfig.GateConfig("density", false, Map.of("trusted_authors", List.of("a@b.com")), "fail")
        ), Map.of()).usesTrustedAuthorsBypass());
        assertFalse(new AIVConfig(List.of(
                new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", List.of()), "fail")
        ), Map.of()).usesTrustedAuthorsBypass());
        assertFalse(new AIVConfig(List.of(
                new AIVConfig.GateConfig("design", true, Map.of("trusted_authors", List.of("a@b.com")), "fail")
        ), Map.of()).usesTrustedAuthorsBypass());
    }

    @Test
    void usesTrustedAuthorsBypassWithStringAuthor() {
        var cfg = new AIVConfig(List.of(
                new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", "solo@example.com"), "fail")
        ), Map.of());
        assertTrue(cfg.usesTrustedAuthorsBypass());
    }

    @Test
    void usesTrustedAuthorsBypassFalseWhenTrustedAuthorsNotListOrString() {
        var cfg = new AIVConfig(List.of(
                new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", Integer.valueOf(42)), "fail")
        ), Map.of());
        assertFalse(cfg.usesTrustedAuthorsBypass());
    }
}
