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

        if (shouldHonorSkip(diff, config)) {
            log.warn("Skipping gates: /aiv skip on latest commit (allowlisted author, signed commit)");
            List<String> skipNotices = new ArrayList<>(diff.getWarnings());
            skipNotices.add("[SKIP-HONORED] /aiv skip honored for " + diff.getAuthorEmail()
                    + " (on skip_allowlist, good signature); no gate was evaluated.");
            AIVResult aivResult = new AIVResult(true, List.of(
                    new GateResult("skip", true, "Human override: /aiv skip on latest commit by allowlisted author "
                            + diff.getAuthorEmail() + " (signed)")), skipNotices);
            reportPublisher.publish(aivResult);
            return 0;
        }

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
        AIVContext context = new AIVContext(workspace,
                policyWorkspace == null ? workspace : policyWorkspace, filteredDiff, config, Map.of());

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
            GateResult severityApplied = applySeverity(raw, gate.getId(), config);
            GateResult baselined = applyBaseline(severityApplied, config, context.getPolicyWorkspace());
            GateResult result = InlineDisableFilter.apply(baselined, context);
            String suppressed = suppressionNotice(gate.getId(), severityApplied, result);
            if (suppressed != null) {
                suppressions.add(suppressed);
            }
            results.add(result);
            if (!result.isPassed()) {
                log.info("Gate {} failed: {}", gate.getId(), result.getMessage());
                if (result.blocksCi()) {
                    overallPassed = false;
                    if (failFast) {
                        break;
                    }
                } else {
                    log.warn("Advisory (severity=warn): {} - {}", gate.getId(), result.getMessage());
                }
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
            notices.add("[SKIP-IGNORED] /aiv skip directive found on the head commit but not honored "
                    + "(requires skip_allowlist membership and a good commit signature).");
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
     * Default-deny human override. {@code /aiv skip} is honored only when the project has an explicit
     * {@code skip_allowlist} AND the head commit author is on it AND the head commit carries a good
     * ({@code %G? == G}) signature. An empty allowlist means the directive is ignored: anyone (including
     * a coding agent told to "make CI green") can write a commit message, so an open skip is not a gate.
     */
    static boolean shouldHonorSkip(Diff diff, AIVConfig config) {
        if (!diff.isSkipDirectivePresent()) {
            return false;
        }
        List<String> allow = config.getSkipAllowlist();
        if (allow.isEmpty()) {
            log.warn("/aiv skip present but ignored: skip_allowlist is empty (default-deny)");
            return false;
        }
        if (!diff.isHeadCommitSigned()) {
            log.warn("/aiv skip present but ignored: head commit is not signed with a good signature");
            return false;
        }
        String author = diff.getAuthorEmail();
        if (author == null || author.isBlank()) {
            return false;
        }
        return allow.stream().anyMatch(a -> a.equalsIgnoreCase(author.trim()));
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
            java.util.Set.of("doc-integrity", "security", "license-header", "pr-metadata", "external", "rules", "jev");

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
