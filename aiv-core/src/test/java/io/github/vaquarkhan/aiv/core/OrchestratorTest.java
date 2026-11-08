/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.*;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import io.github.vaquarkhan.aiv.port.ReportPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
    void doctorRunWithTrustedAuthorsAndSkipAllowlistEmitsPolicyHints() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "");
            }
        };
        List<AIVConfig.GateConfig> gates = new ArrayList<>(allGatesDisabled());
        gates.add(new AIVConfig.GateConfig("density", true,
                Map.of("trusted_authors", List.of("team@example.com")), "fail"));
        AIVConfig cfg = new AIVConfig(gates, Map.of("skip_allowlist", List.of("boss@example.com")));
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return cfg;
            }
        };
        var reportPublisher = new ReportPublisher() {
            @Override
            public void publish(AIVResult result) {
                // no-op
            }
        };
        var orch = new Orchestrator(diffProvider, configProvider, reportPublisher);
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD", true));
        assertTrue(cfg.usesTrustedAuthorsBypass());
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
    void skipIgnoredWhenAllowlistEmptyDefaultDeny() {
        // Trust boundary: an open /aiv skip (empty allowlist) must never short-circuit the gates.
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, "anyone@example.com", true, true,
                        List.of(), Map.of());
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
        assertTrue(results.get(0).getGateResults().stream().noneMatch(r -> "skip".equals(r.getGateId())));
        assertTrue(results.get(0).getNotices().stream().anyMatch(n -> n.startsWith("[SKIP-IGNORED]")));
    }

    @Test
    void configChangeInDiffIsSurfacedAsNotice() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(
                        new ChangedFile(".aiv/config.yaml", ChangedFile.ChangeType.MODIFIED, "gates: []"),
                        new ChangedFile("sub\\.aiv\\design-rules.yaml", ChangedFile.ChangeType.ADDED, "x"),
                        new ChangedFile("src/App.java", ChangedFile.ChangeType.MODIFIED, "class A {}")), "");
            }
        };
        var configProvider = new ConfigProvider() {
            @Override
            public AIVConfig getConfig(java.nio.file.Path workspace) {
                return new AIVConfig(allGatesDisabled(), Map.of());
            }
        };
        var results = new ArrayList<AIVResult>();
        var orch = new Orchestrator(diffProvider, configProvider, results::add);
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        String notice = results.get(0).getNotices().stream()
                .filter(n -> n.startsWith("[CONFIG-CHANGE]")).findFirst().orElse("");
        assertTrue(notice.contains(".aiv/config.yaml"), notice);
        assertTrue(notice.contains("sub/.aiv/design-rules.yaml"), notice);
        assertEquals(List.of(".aiv/config.yaml", "sub/.aiv/design-rules.yaml"),
                Orchestrator.changedAivConfigPaths(diffProvider.getDiff(null, "a", "b")));
    }

    @Test
    void protectedPolicyChangeIsBlocking() {
        DiffProvider diffProvider = (workspace, baseRef, headRef) -> new Diff(baseRef, headRef, List.of(
                new ChangedFile(".aiv/rules/team.yaml", ChangedFile.ChangeType.MODIFIED, "rules: []")), "");
        ConfigProvider configProvider = workspace -> new AIVConfig(allGatesDisabled(),
                Map.of("protect_policy_paths", true));
        List<AIVResult> results = new ArrayList<>();
        Orchestrator orchestrator = new Orchestrator(diffProvider, configProvider, results::add);
        assertEquals(1, orchestrator.run(Paths.get("."), "main", "HEAD"));
        GateResult policy = results.get(0).getGateResults().get(0);
        assertEquals("policy-integrity", policy.getGateId());
        assertTrue(policy.blocksCi());
        assertEquals("policy-integrity.changed", policy.getFindings().get(0).getRuleId());
    }

    @Test
    void skipRequiresGoodSignatureEvenForAllowlistedAuthor() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                // allowlisted author, skip directive present, but NOT signed
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
        var orch = new Orchestrator(diffProvider, configProvider, results::add);
        assertEquals(0, orch.run(Paths.get("."), "main", "HEAD"));
        assertTrue(results.get(0).getGateResults().stream().noneMatch(r -> "skip".equals(r.getGateId())));
    }

    @Test
    void skipIgnoredWhenAllowlistDoesNotMatchAuthor() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, "dev@example.com", true, true,
                        List.of(), Map.of());
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
    void skipRemainsDisabledEvenWhenAuthorAndSignatureAppearAllowlisted() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, "boss@company.com", true, true,
                        List.of(), Map.of());
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
        assertTrue(results.get(0).getGateResults().stream().noneMatch(r -> "skip".equals(r.getGateId())));
        assertTrue(results.get(0).getNotices().stream().anyMatch(n -> n.startsWith("[SKIP-IGNORED]")));
    }

    @Test
    void skipNotHonoredWhenAllowlistSetButAuthorMissing() {
        var diffProvider = new DiffProvider() {
            @Override
            public Diff getDiff(java.nio.file.Path workspace, String baseRef, String headRef) {
                return new Diff(baseRef, headRef, List.of(), "", 0, 0, null, true, true, List.of(), Map.of());
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
    void ruleActionsSplitHardAdvisoryAndOffFindings() {
        GateResult raw = GateResult.fail("invariant", "mixed", List.of(
                Finding.atLine("invariant.merge-conflict", "a.py", 1, "conflict"),
                Finding.atLine("invariant.placeholder", "a.py", 2, "placeholder"),
                Finding.atLine("invariant.ai-provenance", "a.py", 3, "provenance")));
        AIVConfig cfg = new AIVConfig(List.of(new AIVConfig.GateConfig("invariant", true, Map.of(
                "rule_actions", Map.of(
                        "invariant.merge-conflict", "fail",
                        "invariant.placeholder", "warn",
                        "invariant.ai-provenance", "off")), "warn")), Map.of());

        List<GateResult> split = Orchestrator.applyRuleActions(raw, "invariant", cfg);
        assertEquals(2, split.size());
        assertTrue(split.get(0).blocksCi());
        assertEquals("invariant.merge-conflict", split.get(0).getFindings().get(0).getRuleId());
        assertFalse(split.get(1).blocksCi());
        assertEquals("invariant.placeholder", split.get(1).getFindings().get(0).getRuleId());
    }

    @Test
    void gateRatedAdvisoryRulesStayAdvisoryInsideABlockingResult() {
        GateResult raw = new GateResult("change-policy", false, "mixed", true, List.of(
                Finding.atLine("policy.untested", "(diff)", 1, "[FAIL] x"),
                Finding.atLine("policy.size", "(diff)", 1, "[WARN] y"),
                Finding.atLine("policy.churn", "(diff)", 1, "[WARN] z")), java.util.Set.of("policy.size", "policy.churn"));
        AIVConfig cfg = new AIVConfig(List.of(new AIVConfig.GateConfig("change-policy", true,
                Map.of("rule_actions", Map.of("policy.churn", "fail")))), Map.of());
        List<GateResult> split = Orchestrator.applyRuleActions(raw, "change-policy", cfg);
        assertEquals(2, split.size());
        assertEquals(List.of("policy.untested", "policy.churn"),
                split.get(0).getFindings().stream().map(Finding::getRuleId).toList(), "project rule_actions still win");
        assertEquals(List.of("policy.size"), split.get(1).getFindings().stream().map(Finding::getRuleId).toList());
        assertFalse(split.get(1).blocksCi());
    }

    @Test
    void ruleActionCannotPromoteIntrinsicallyAdvisoryGate() {
        GateResult raw = GateResult.advisory("density", "signal", List.of(
                Finding.atLine("density.entropy", "a.py", 1, "entropy")));
        AIVConfig cfg = new AIVConfig(List.of(new AIVConfig.GateConfig("density", true,
                Map.of("rule_actions", Map.of("density.entropy", "fail")))), Map.of());
        GateResult result = Orchestrator.applyRuleActions(raw, "density", cfg).get(0);
        assertFalse(result.blocksCi());
    }

    @Test
    void unsuppressibleRuleIgnoresInlineDirective(@TempDir Path dir) throws Exception {
        String content = "# aiv-disable-next-line invariant.merge-conflict\n<<<<<<< HEAD\n";
        ChangedFile file = new ChangedFile("a.py", ChangedFile.ChangeType.MODIFIED, content);
        AIVConfig cfg = new AIVConfig(List.of(new AIVConfig.GateConfig("invariant", true,
                Map.of("unsuppressible_rules", List.of("invariant.merge-conflict")))), Map.of());
        AIVContext ctx = new AIVContext(dir, new Diff("main", "HEAD", List.of(file), ""), cfg);
        GateResult raw = GateResult.fail("invariant", "conflict", List.of(
                Finding.atLine("invariant.merge-conflict", "a.py", 2, "conflict")));
        assertFalse(Orchestrator.applySuppressions(raw, "invariant", cfg, ctx).isPassed());
    }

    @Test
    void unsuppressibleAndSuppressibleFindingsAreFilteredIndependently() {
        String content = "# aiv-disable-next-line invariant.placeholder\n# FIXME\n<<<<<<< HEAD\n";
        ChangedFile file = new ChangedFile("a.py", ChangedFile.ChangeType.MODIFIED, content);
        AIVConfig cfg = new AIVConfig(List.of(new AIVConfig.GateConfig("invariant", true,
                Map.of("unsuppressible_rules", List.of("invariant.merge-conflict")))), Map.of());
        AIVContext ctx = new AIVContext(Path.of("."), new Diff("main", "HEAD", List.of(file), ""), cfg);
        GateResult raw = GateResult.fail("invariant", "mixed", List.of(
                Finding.atLine("invariant.merge-conflict", "a.py", 3, "conflict"),
                Finding.atLine("invariant.placeholder", "a.py", 2, "placeholder")));
        GateResult result = Orchestrator.applySuppressions(raw, "invariant", cfg, ctx);
        assertTrue(result.blocksCi());
        assertEquals(List.of("invariant.merge-conflict"),
                result.getFindings().stream().map(Finding::getRuleId).toList());
    }

    @Test
    void unsuppressibleAdvisoryRemainsAdvisoryWithSuppressiblePeer() {
        ChangedFile file = new ChangedFile("a.py", ChangedFile.ChangeType.MODIFIED, "x = 1\n");
        AIVConfig cfg = new AIVConfig(List.of(new AIVConfig.GateConfig("density", true,
                Map.of("unsuppressible_rules", List.of("density.entropy")), "warn")), Map.of());
        AIVContext ctx = new AIVContext(Path.of("."), new Diff("main", "HEAD", List.of(file), ""), cfg);
        GateResult raw = GateResult.advisory("density", "mixed", List.of(
                Finding.atLine("density.entropy", "a.py", 1, "entropy"),
                Finding.atLine("density.other", "a.py", 1, "other")));
        GateResult result = Orchestrator.applySuppressions(raw, "density", cfg, ctx);
        assertFalse(result.blocksCi());
        assertEquals(2, result.getFindings().size());
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
        assertTrue(results.get(0).getNotices().stream().anyMatch(n -> n.contains("[DOCTOR]")));
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
                                "expected_changed_files", 1,
                                "expected_excluded_paths", "[src/generated/Foo.java]"
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

    @Test
    void applyBaselineNoopWithoutConfig() {
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "FIXME")));
        var out = Orchestrator.applyBaseline(raw, new AIVConfig(List.of(), Map.of()), Paths.get("."));
        assertFalse(out.isPassed());
    }

    @Test
    void applyBaselineSuppressesFromFile(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("b.txt");
        Files.writeString(base, "invariant.placeholder|a.java\n");
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "FIXME")));
        var cfg = new AIVConfig(List.of(), Map.of("baseline", base.toAbsolutePath().toString()));
        assertTrue(Orchestrator.applyBaseline(raw, cfg, dir).isPassed());
    }

    @Test
    void applyBaselineRelativePath(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("rel.txt");
        Files.writeString(base, "invariant.placeholder|a.java\n");
        var raw = GateResult.fail("invariant", "x", List.of(
                Finding.atLine("invariant.placeholder", "a.java", 1, "FIXME")));
        var cfg = new AIVConfig(List.of(), Map.of("baseline", "rel.txt"));
        assertTrue(Orchestrator.applyBaseline(raw, cfg, dir).isPassed());
    }

    @Test
    void applyBaselineMissingFileThrows(@TempDir Path dir) {
        var raw = GateResult.fail("invariant", "x");
        var cfg = new AIVConfig(List.of(), Map.of("baseline", "missing.txt"));
        assertThrows(IllegalArgumentException.class, () -> Orchestrator.applyBaseline(raw, cfg, dir));
    }
}
