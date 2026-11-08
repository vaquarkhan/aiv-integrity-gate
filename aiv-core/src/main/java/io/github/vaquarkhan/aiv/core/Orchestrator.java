/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.port.ReportPublisher;
import io.github.vaquarkhan.aiv.util.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;


/**
 * Runs the pipeline: diff - config - gates (in order) - publish.
 *
 * @author Vaquar Khan
 */
public final class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);
    private final DiffProvider diffProvider;
    private final ConfigProvider configProvider;
    private final ReportPublisher reportPublisher;
    private final Path policyWorkspace;

    public Orchestrator(DiffProvider diffProvider, ConfigProvider configProvider, ReportPublisher reportPublisher) {
        this(diffProvider, configProvider, reportPublisher, null);
    }

    public Orchestrator(DiffProvider diffProvider, ConfigProvider configProvider, ReportPublisher reportPublisher,
                        Path policyWorkspace) {
        this.diffProvider = diffProvider;
        this.configProvider = configProvider;
        this.reportPublisher = reportPublisher;
        this.policyWorkspace = policyWorkspace;
    }

    /**
     * Run all enabled gates. Returns exit code: 0 = pass, 1 = fail.
     */
    public int run(Path workspace, String baseRef, String headRef) {
        return run(workspace, baseRef, headRef, false);
    }

    /**
     * @param doctor when true, always returns 0 if the diff was computed (informational / tuning run).
     */
    public int run(Path workspace, String baseRef, String headRef, boolean doctor) {
        Diff diff = diffProvider.getDiff(workspace, baseRef, headRef);
        AIVConfig config = configProvider.getConfig(workspace);
        warnRelaxedIdentityHooks(config);

        // Record and ignore unauthenticated skip directives. No gate bypass occurs.
        shouldHonorSkip(diff, config);

        List<ChangedFile> filtered = diff.getChangedFiles().stream()
                .filter(f -> !PathFilter.isExcluded(f.getPath(), config.getExcludePaths()))
                .collect(Collectors.toList());
        if (filtered.size() < diff.getChangedFiles().size()) {
            log.debug("Excluded {} paths from validation", diff.getChangedFiles().size() - filtered.size());
        }
        Map<String, Integer> filteredLoc = new HashMap<>();
        for (ChangedFile f : filtered) {
            Integer v = diff.getNetLocForFile(f.getPath());
            if (v != null) {
                filteredLoc.put(f.getPath().replace("\\", "/"), v);
            }
        }
        Diff filteredDiff = new Diff(diff.getBaseRef(), diff.getHeadRef(), filtered, diff.getRawDiff(),
                diff.getLinesAdded(), diff.getLinesDeleted(), diff.getAuthorEmail(), diff.isHeadCommitSigned(),
                diff.isSkipDirectivePresent(),
                diff.getWarnings(), filteredLoc);
        List<ChangedFile> excluded = diff.getChangedFiles().stream()
                .filter(f -> PathFilter.isExcluded(f.getPath(), config.getExcludePaths()))
                .collect(Collectors.toList());
        AIVContext context = new AIVContext(workspace,
                policyWorkspace == null ? workspace : policyWorkspace, filteredDiff, config,
                Map.of(AIVContext.EXCLUDED_CHANGED_FILES, excluded));

        List<QualityGate> gates = loadGates();
        List<GateResult> results = new ArrayList<>();
        List<String> suppressions = new ArrayList<>();
        boolean overallPassed = true;
        boolean failFast = config.isFailFast();
        List<String> protectedPolicyChanges = changedAivConfigPaths(diff);
        if (config.getGlobalBoolean("protect_policy_paths", false) && !protectedPolicyChanges.isEmpty()) {
            List<Finding> policyFindings = new ArrayList<>();
            for (String path : protectedPolicyChanges) {
                policyFindings.add(Finding.atLine("policy-integrity.changed", path, 1,
                        "Protected policy file changed; evaluate policy changes in a separately approved run"));
            }
            results.add(GateResult.fail("policy-integrity",
                    "Protected .aiv policy files differ from the trusted base ref", policyFindings));
            overallPassed = false;
        }

        for (QualityGate gate : gates) {
            if (!isGateEnabled(gate.getId(), config, filtered)) {
                continue;
            }
            GateResult raw = gate.evaluate(context);
            List<GateResult> actionResults = applyRuleActions(raw, gate.getId(), config);
            boolean stop = false;
            for (GateResult actionResult : actionResults) {
                GateResult result = applySuppressions(actionResult, gate.getId(), config, context);
                String suppressed = suppressionNotice(gate.getId(), actionResult, result);
                if (suppressed != null) {
                    suppressions.add(suppressed);
                }
                results.add(result);
                if (!result.isPassed()) {
                    log.info("Gate {} failed: {}", gate.getId(), result.getMessage());
                    if (result.blocksCi()) {
                        overallPassed = false;
                        stop = failFast;
                    } else {
                        log.warn("Advisory: {} - {}", gate.getId(), result.getMessage());
                    }
                }
            }
            if (stop) {
                break;
            }
        }

        List<String> notices = new ArrayList<>(filteredDiff.getWarnings());
        List<String> cfgChanges = changedAivConfigPaths(diff);
        if (!cfgChanges.isEmpty()) {
            notices.add("[CONFIG-CHANGE] This change modifies AIV configuration: " + String.join(", ", cfgChanges)
                    + ". Verdict must be computed with the base-ref config (CLI default); review the change deliberately.");
        }
        notices.addAll(suppressions);
        if (diff.isSkipDirectivePresent()) {
            notices.add("[SKIP-IGNORED] /aiv skip directive found on the pinned head commit; "
                    + "unauthenticated full-run bypass is disabled.");
        }
        if (doctor) {
            notices.add(0, "[DOCTOR] Informational run: gate outcomes are reported but not CI-blocking.");
        }
        AIVResult aivResult = new AIVResult(overallPassed, results, notices);
        reportPublisher.publish(aivResult);
        if (doctor) {
            log.info("Doctor mode: informational exit 0");
            return 0;
        }
        return overallPassed ? 0 : 1;
    }

    static GateResult applySeverity(GateResult raw, String gateId, AIVConfig config) {
        if (raw.isPassed() || !raw.blocksCi()) {
            return raw;
        }
        String sev = config.findGate(gateId).map(AIVConfig.GateConfig::getSeverity).orElse("fail");
        if (!"warn".equals(sev)) {
            return raw;
        }
        return GateResult.advisory(gateId, raw.getMessage(), raw.getFindings());
    }

    /**
     * Applies rule-specific actions before suppression. Mixed hard/advisory findings are represented as
     * separate results with the same stable gate id so existing publishers remain schema-compatible.
     * Intrinsically advisory gates cannot be promoted to blocking by configuration.
     */
    static List<GateResult> applyRuleActions(GateResult raw, String gateId, AIVConfig config) {
        if (raw == null || raw.isPassed() || raw.getFindings().isEmpty()) {
            return List.of(applySeverity(raw, gateId, config));
        }
        String gateDefault = raw.blocksCi()
                ? config.findGate(gateId).map(AIVConfig.GateConfig::getSeverity).orElse("fail")
                : "warn";
        List<Finding> hard = new ArrayList<>();
        List<Finding> advisory = new ArrayList<>();
        for (Finding finding : raw.getFindings()) {
            String ruleDefault = raw.getAdvisoryRules().contains(finding.getRuleId()) ? "warn" : gateDefault;
            String action = config.getRuleAction(gateId, finding.getRuleId(), ruleDefault);
            if ("off".equals(action)) {
                continue;
            }
            if ("fail".equals(action) && raw.blocksCi()) {
                hard.add(finding);
            } else {
                advisory.add(finding);
            }
        }
        List<GateResult> out = new ArrayList<>();
        if (!hard.isEmpty()) {
            out.add(GateResult.fail(gateId, findingMessages(hard), hard));
        }
        if (!advisory.isEmpty()) {
            out.add(GateResult.advisory(gateId, findingMessages(advisory), advisory));
        }
        return out.isEmpty() ? List.of(GateResult.pass(gateId)) : List.copyOf(out);
    }

    static GateResult applySuppressions(GateResult raw, String gateId, AIVConfig config, AIVContext context) {
        if (raw == null || raw.isPassed() || raw.getFindings().isEmpty()) {
            return raw;
        }
        List<Finding> protectedFindings = raw.getFindings().stream()
                .filter(f -> config.isRuleUnsuppressible(gateId, f.getRuleId())).toList();
        if (protectedFindings.isEmpty()) {
            return InlineDisableFilter.apply(
                    applyBaseline(raw, config, context.getPolicyWorkspace()), context);
        }
        List<Finding> suppressible = raw.getFindings().stream()
                .filter(f -> !config.isRuleUnsuppressible(gateId, f.getRuleId())).toList();
        List<Finding> kept = new ArrayList<>(protectedFindings);
        if (!suppressible.isEmpty()) {
            GateResult candidate = raw.blocksCi()
                    ? GateResult.fail(gateId, findingMessages(suppressible), suppressible)
                    : GateResult.advisory(gateId, findingMessages(suppressible), suppressible);
            GateResult filtered = InlineDisableFilter.apply(
                    applyBaseline(candidate, config, context.getPolicyWorkspace()), context);
            kept.addAll(filtered.getFindings());
        }
        return raw.blocksCi()
                ? GateResult.fail(gateId, findingMessages(kept), kept)
                : GateResult.advisory(gateId, findingMessages(kept), kept);
    }

    private static String findingMessages(List<Finding> findings) {
        return findings.stream().map(Finding::getMessage).collect(Collectors.joining("\n"));
    }

    /**
     * Visible record of findings removed by the baseline or inline {@code aiv-disable} directives, so a
     * suppression is never silent: it reaches the report, the JSON output and the audit chain.
     */
    static String suppressionNotice(String gateId, GateResult before, GateResult after) {
        int removed = before.getFindings().size() - after.getFindings().size();
        if (removed <= 0) {
            return null;
        }
        java.util.TreeSet<String> rules = new java.util.TreeSet<>();
        List<Finding> kept = after.getFindings();
        for (Finding f : before.getFindings()) {
            if (!kept.contains(f)) {
                rules.add(f.getRuleId());
            }
        }
        return "[SUPPRESSED] " + gateId + ": " + removed + " finding" + (removed == 1 ? "" : "s")
                + " removed by baseline or inline aiv-disable (rules: " + String.join(", ", rules) + ")";
    }

    static GateResult applyBaseline(GateResult raw, AIVConfig config, Path workspace) {
        String baselineRel = config.getGlobalString("baseline").orElse("");
        if (baselineRel.isBlank()) {
            return raw;
        }
        Path baselinePath = Path.of(baselineRel);
        if (!baselinePath.isAbsolute()) {
            baselinePath = workspace.resolve(baselineRel).normalize();
        }
        try {
            return BaselineFilter.load(baselinePath).apply(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read baseline: " + e.getMessage(), e);
        }
    }

    /**
     * Full-run skip directives are disabled. Git author email is caller-controlled and {@code %G? == G}
     * proves only that some trusted signature exists, not that it belongs to an allowlisted identity.
     * A future break-glass path must bind a signer fingerprint and one pinned commit OID in a signed receipt.
     */
    static boolean shouldHonorSkip(Diff diff, AIVConfig config) {
        if (diff.isSkipDirectivePresent()) {
            log.warn("/aiv skip present but ignored: unauthenticated full-run bypass is disabled");
        }
        return false;
    }

    /** Paths under {@code .aiv/} that a PR touched; reported so reviewers see a gate-config change. */
    static List<String> changedAivConfigPaths(Diff diff) {
        List<String> out = new ArrayList<>();
        for (ChangedFile f : diff.getChangedFiles()) {
            String p = f.getPath() == null ? "" : f.getPath().replace('\\', '/');
            if (p.startsWith(".aiv/") || p.contains("/.aiv/")) {
                out.add(p);
            }
        }
        return out;
    }

    private List<QualityGate> loadGates() {
        return ServiceLoader.load(QualityGate.class).stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toList());
    }

    /**
     * Gates that are OFF unless explicitly enabled in config. These are opt-in either because they
     * are expensive/noisy ({@code doc-integrity}, {@code security}) or because they enforce
     * ASF-specific policy that a project adopts deliberately ({@code license-header},
     * {@code pr-metadata}) - typically via the {@code asf} preset.
     */
    private static final java.util.Set<String> DEFAULT_OFF_GATES =
            java.util.Set.of("doc-integrity", "security", "license-header", "pr-metadata", "external", "rules", "jev",
                    "review-routing", "change-policy");

    private boolean isGateEnabled(String gateId, AIVConfig config, List<ChangedFile> filteredFiles) {
        boolean enabled = config.getGates().stream()
                .filter(g -> g.getId().equals(gateId))
                .findFirst()
                .map(AIVConfig.GateConfig::isEnabled)
                .orElse(!DEFAULT_OFF_GATES.contains(gateId));
        if (!enabled) return false;
        if (!"doc-integrity".equals(gateId)) return true;
        Object auto = config.getGates().stream()
                .filter(g -> "doc-integrity".equals(g.getId()))
                .findFirst()
                .map(AIVConfig.GateConfig::getConfig)
                .orElse(Map.of())
                .get("auto");
        if (!Boolean.TRUE.equals(auto)) return true;
        return filteredFiles.stream().anyMatch(f -> isDocPath(f.getPath()));
    }

    private static boolean isDocPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase().replace("\\", "/");
        if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".rst")) return true;
        String base = lower.contains("/") ? lower.substring(lower.lastIndexOf("/") + 1) : lower;
        return "agents.md".equals(base) || "claude.md".equals(base)
                || "contributing.md".equals(base) || "readme.md".equals(base);
    }

    private void warnRelaxedIdentityHooks(AIVConfig config) {
        if (config.usesTrustedAuthorsBypass()) {
            log.warn("density gate trusted_authors matches git commit author email from metadata only; "
                    + "it is not a cryptographic identity check and can be spoofed with local git config.");
        }
        if (!config.getSkipAllowlist().isEmpty()) {
            log.warn("skip_allowlist restricts /aiv skip by author email from git metadata only; "
                    + "use branch protection and code review for real enforcement.");
        }
    }
}
