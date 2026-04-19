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

import io.github.vaquarkhan.aiv.model.*;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import io.github.vaquarkhan.aiv.port.ReportPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class OrchestratorTest {

    private static List<AIVConfig.GateConfig> allGatesDisabled() {
        return List.of(
                new AIVConfig.GateConfig("pass", false, Map.of()),
                new AIVConfig.GateConfig("disabled", false, Map.of()),
                new AIVConfig.GateConfig("fail", false, Map.of()),
                new AIVConfig.GateConfig("afterfail", false, Map.of()),
                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
        );
    }

    /** Every test gate explicit; only doc-integrity runs (others disabled). */
    private static AIVConfig configOnlyDocIntegrityEnabled(Map<String, Object> docIntegrityGateOptions) {
        return new AIVConfig(
                List.of(
                        new AIVConfig.GateConfig("pass", false, Map.of()),
                        new AIVConfig.GateConfig("disabled", false, Map.of()),
                        new AIVConfig.GateConfig("fail", false, Map.of()),
                        new AIVConfig.GateConfig("afterfail", false, Map.of()),
                        new AIVConfig.GateConfig("assert-files", false, Map.of()),
                        new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                        new AIVConfig.GateConfig("doc-integrity", true, docIntegrityGateOptions)
                ),
                Map.of());
    }

    @Test
    void propagatesDiffWarningsToReport() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef,
                        List.of(new ChangedFile("src/App.java", ChangedFile.ChangeType.MODIFIED, "x")),
                        "", 0, 0, null, false,
                        List.of("File skipped (oversized): big.bin"),
                        Map.of("src/App.java", -2));
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(allGatesDisabled(), Map.of());
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
        assertTrue(results.get(0).getNotices().stream().anyMatch(n -> n.contains("big.bin")));
    }

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
                return new AIVConfig(allGatesDisabled(), Map.of());
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
    void skipsAllGatesWhenSkipRequested() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, null, true);
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(allGatesDisabled(), Map.of());
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
        assertTrue(results.get(0).getGateResults().stream().anyMatch(r -> "skip".equals(r.getGateId())));
    }

    @Test
    void skipIgnoredWhenAllowlistDoesNotMatchAuthor() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, "dev@example.com", true);
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", true, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", true, Map.of()),
                                new AIVConfig.GateConfig("afterfail", false, Map.of()),
                                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
                        ),
                        Map.of("fail_fast", true, "skip_allowlist", List.of("boss@company.com"))
                );
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
        assertEquals(1, code);
        assertEquals(List.of("pass", "fail"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
    }

    @Test
    void skipHonoredWhenAuthorMatchesAllowlist() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, "boss@company.com", true);
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(allGatesDisabled(), Map.of("skip_allowlist", List.of("boss@company.com")));
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
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        assertTrue(results.get(0).getGateResults().stream().anyMatch(r -> "skip".equals(r.getGateId())));
    }

    @Test
    void skipNotHonoredWhenAllowlistSetButAuthorMissing() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, null, true);
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", true, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", true, Map.of()),
                                new AIVConfig.GateConfig("afterfail", false, Map.of()),
                                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
                        ),
                        Map.of("fail_fast", true, "skip_allowlist", List.of("boss@company.com"))
                );
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
        assertEquals(1, orch.run(Paths.get("."), "main", "HEAD"));
        assertEquals(List.of("pass", "fail"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
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
                return new AIVConfig(allGatesDisabled(), Map.of());
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

    @Test
    void aggregatesMultipleGateResultsWhenFailFastFalse() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", true, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", true, Map.of()),
                                new AIVConfig.GateConfig("afterfail", true, Map.of()),
                                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
                        ),
                        Map.of("fail_fast", false)
                );
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
        assertEquals(1, orch.run(Paths.get("."), "main", "HEAD"));
        List<String> ids = results.get(0).getGateResults().stream().map(GateResult::getGateId).toList();
        assertTrue(ids.contains("pass"));
        assertTrue(ids.contains("fail"));
        assertTrue(ids.contains("afterfail"));
    }

    @Test
    void runsEnabledGatesSkipsDisabledAndStopsAfterFirstFailure() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", true, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", true, Map.of()),
                                new AIVConfig.GateConfig("afterfail", true, Map.of()),
                                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
                        ),
                        Map.of("fail_fast", true)
                );
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

        assertEquals(1, code);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isPassed());
        assertEquals(List.of("pass", "fail"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
    }

    @Test
    void doctorModeReturnsZeroEvenWhenGateFails() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", true, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", true, Map.of()),
                                new AIVConfig.GateConfig("afterfail", false, Map.of()),
                                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
                        ),
                        Map.of("fail_fast", true)
                );
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
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD", true));
        assertEquals(1, results.size());
        assertFalse(results.get(0).isPassed());
    }

    @Test
    void warnSeverityRunsAdvisoryPathWithoutFailingRun() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", true, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", true, Map.of(), "warn"),
                                new AIVConfig.GateConfig("afterfail", true, Map.of()),
                                new AIVConfig.GateConfig("assert-files", false, Map.of()),
                                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
                        ),
                        Map.of("fail_fast", false)
                );
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
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        assertEquals(1, results.size());
        assertTrue(results.get(0).isPassed());
        var failGr = results.get(0).getGateResults().stream()
                .filter(r -> "fail".equals(r.getGateId()))
                .findFirst()
                .orElseThrow();
        assertFalse(failGr.isPassed());
        assertFalse(failGr.blocksCi());
    }

    @Test
    void excludesPathsBeforeRunningGates() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(
                        baseRef,
                        headRef,
                        List.of(
                                new ChangedFile("src/generated/Foo.java", ChangedFile.ChangeType.ADDED, "x"),
                                new ChangedFile("src/main/java/App.java", ChangedFile.ChangeType.MODIFIED, "y")
                        ),
                        ""
                );
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", false, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", false, Map.of()),
                                new AIVConfig.GateConfig("afterfail", false, Map.of()),
                                new AIVConfig.GateConfig("assert-files", true, Map.of()),
                                new AIVConfig.GateConfig("default-enabled", false, Map.of()),
                                new AIVConfig.GateConfig("doc-integrity", false, Map.of())
                        ),
                        Map.of(
                                "exclude_paths", List.of("**/generated/**"),
                                "expected_changed_files", 1
                        )
                );
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
        assertEquals(List.of("assert-files"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
    }

    @Test
    void gateNotInConfigDefaultsToEnabled() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                // Intentionally omit "default-enabled" to cover the default-enabled behavior (orElse(true)).
                return new AIVConfig(
                        List.of(
                                new AIVConfig.GateConfig("pass", false, Map.of()),
                                new AIVConfig.GateConfig("disabled", false, Map.of()),
                                new AIVConfig.GateConfig("fail", false, Map.of()),
                                new AIVConfig.GateConfig("afterfail", false, Map.of()),
                                new AIVConfig.GateConfig("assert-files", false, Map.of())
                        ),
                        Map.of()
                );
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
        assertEquals(List.of("default-enabled"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
    }

    @Test
    void excludesPathsAndLogsWhenFiltering(@TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("gen/foo"));
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(
                        baseRef, headRef,
                        List.of(
                                new ChangedFile("gen/foo/X.java", ChangedFile.ChangeType.ADDED, "x"),
                                new ChangedFile("src/Main.java", ChangedFile.ChangeType.MODIFIED, "y")
                        ),
                        ""
                );
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(
                        allGatesDisabled(),
                        Map.of("exclude_paths", List.of("**/gen/**"))
                );
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
        orch.run(dir, "main", "HEAD");
        assertEquals(1, results.size());
    }

    @Test
    void docIntegrityRunsWhenEnabledWithoutAuto() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef,
                        List.of(new ChangedFile("src/Foo.java", ChangedFile.ChangeType.MODIFIED, "x")),
                        "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(Path workspace) {
                return configOnlyDocIntegrityEnabled(Map.of());
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
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        assertEquals(List.of("doc-integrity"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
    }

    @Test
    void docIntegritySkippedWhenAutoWithoutDocPaths() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef,
                        List.of(new ChangedFile("src/Foo.java", ChangedFile.ChangeType.MODIFIED, "x")),
                        "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(Path workspace) {
                return configOnlyDocIntegrityEnabled(Map.of("auto", Boolean.TRUE));
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
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        assertTrue(results.get(0).getGateResults().isEmpty());
    }

    @Test
    void docIntegrityRunsWhenAutoWithDocLikePaths() {
        var paths = List.of(
                new ChangedFile("docs/a.md", ChangedFile.ChangeType.ADDED, ""),
                new ChangedFile("notes.txt", ChangedFile.ChangeType.MODIFIED, ""),
                new ChangedFile("guide.rst", ChangedFile.ChangeType.ADDED, ""),
                new ChangedFile("dir\\Mixed.MD", ChangedFile.ChangeType.ADDED, ""),
                new ChangedFile("AGENTS.md", ChangedFile.ChangeType.ADDED, ""),
                new ChangedFile("subdir\\readme.md", ChangedFile.ChangeType.ADDED, ""),
                new ChangedFile("CONTRIBUTING.md", ChangedFile.ChangeType.ADDED, ""),
                new ChangedFile("CLAUDE.md", ChangedFile.ChangeType.ADDED, ""),
                new ChangedFile(null, ChangedFile.ChangeType.ADDED, ""));
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, paths, "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(Path workspace) {
                return configOnlyDocIntegrityEnabled(Map.of("auto", Boolean.TRUE));
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
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        assertEquals(List.of("doc-integrity"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
    }

    @Test
    void docIntegrityRunsWhenAutoIsNotStrictTrue() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef,
                        List.of(new ChangedFile("x.java", ChangedFile.ChangeType.MODIFIED, "")),
                        "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(Path workspace) {
                return configOnlyDocIntegrityEnabled(Map.of("auto", "yes"));
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
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        assertEquals(List.of("doc-integrity"),
                results.get(0).getGateResults().stream().map(GateResult::getGateId).toList());
    }
}
