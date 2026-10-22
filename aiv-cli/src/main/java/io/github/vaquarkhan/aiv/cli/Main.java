/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.adapter.git.GitDiffProvider;
import io.github.vaquarkhan.aiv.adapter.git.MemoryDiffProvider;
import io.github.vaquarkhan.aiv.adapter.github.GithubChecksPublisher;
import io.github.vaquarkhan.aiv.adapter.github.GithubPrDeterministicFindingsPublisher;
import io.github.vaquarkhan.aiv.adapter.github.GithubPrLabelPublisher;
import io.github.vaquarkhan.aiv.adapter.github.GithubPrReviewCommentsPublisher;
import io.github.vaquarkhan.aiv.adapter.github.StdoutReportPublisher;
import io.github.vaquarkhan.aiv.cli.config.DocChecksConfigProvider;
import io.github.vaquarkhan.aiv.cli.config.GitRefConfigProvider;
import io.github.vaquarkhan.aiv.cli.config.YamlConfigProvider;
import io.github.vaquarkhan.aiv.core.Orchestrator;
import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * CLI entry point.
 *
 * @author Vaquar Khan
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    static IntConsumer EXIT = System::exit;

    /** Overridable for tests; production uses {@link System#getenv(String)}. */
    static Function<String, String> GITHUB_ENV = System::getenv;

    public static void main(String[] args) {
        EXIT.accept(run(args));
    }

    static int run(String[] args) {
        for (String a : args) {
            if ("--version".equals(a) || "-V".equals(a)) {
                String v = cliVersion();
                System.out.println("aiv-cli " + v);
                return 0;
            }
        }

        if (args.length > 0 && "init".equals(args[0])) {
            try {
                String[] initArgs = tail(args);
                String preset = null;
                for (int i = 0; i < initArgs.length; i++) {
                    if ("--preset".equals(initArgs[i]) && i + 1 < initArgs.length) {
                        preset = initArgs[++i];
                    }
                }
                return InitCommand.run(parseWorkspace(initArgs), preset);
            } catch (IOException e) {
                log.error("init failed: {}", e.getMessage());
                return 1;
            }
        }
        if (args.length > 0 && "audit".equals(args[0])) {
            return EvidenceCommands.audit(tail(args), System.out, System.err);
        }
        if (args.length > 0 && "report".equals(args[0])) {
            return EvidenceCommands.report(tail(args), System.out, System.err);
        }
        if (args.length > 0 && "proposal".equals(args[0])) {
            return io.github.vaquarkhan.aiv.proposal.ProposalCli.run(tail(args));
        }
        if (args.length > 0 && "verify".equals(args[0])) {
            return VerifyCommand.run(tail(args), System.in, System.out, System.err);
        }
        if (args.length > 0 && "explain".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("Usage: java -jar aiv-cli.jar explain <gate-id>");
                return 1;
            }
            return ExplainCommand.run(args[1]);
        }

        boolean doctor = args.length > 0 && "doctor".equals(args[0]);
        String[] gateArgs = doctor ? tail(args) : args;

        Path workspace = Paths.get(".").toAbsolutePath();
        String baseRef = "origin/main";
        String headRef = "HEAD";
        boolean includeDocChecks = false;
        Path jsonOutputPath = null;
        Path sarifOutputPath = null;
        boolean publishGithubChecks = false;
        boolean labelPrOnAdvisory = false;
        String advisoryLabelOverride = null;
        int warningsExitCode = 0;
        boolean quiet = false;
        Path baselinePath = null;
        Path diffJsonPath = null;
        boolean publishPrComments = false;
        boolean publishAiSlopLabel = false;
        boolean publishAiSlopComment = false;
        String aiSlopLabelOverride = null;
        boolean mechanicalFix = false;
        boolean configFromWorkspace = false;
        boolean requireTrustedConfig = false;
        String auditLogArg = null;

        for (int i = 0; i < gateArgs.length; i++) {
            if ("--quiet".equals(gateArgs[i])) {
                quiet = true;
            } else if ("--audit-log".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                auditLogArg = gateArgs[++i];
            } else if ("--config-from-workspace".equals(gateArgs[i])) {
                configFromWorkspace = true;
            } else if ("--require-trusted-config".equals(gateArgs[i])) {
                requireTrustedConfig = true;
            } else if ("--workspace".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                workspace = Paths.get(gateArgs[++i]).toAbsolutePath();
            } else if ("--diff".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                baseRef = gateArgs[++i];
            } else if ("--head".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                headRef = gateArgs[++i];
            } else if ("--diff-json".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                diffJsonPath = Paths.get(gateArgs[++i]);
            } else if ("--fix".equals(gateArgs[i])) {
                mechanicalFix = true;
            } else if ("--include-doc-checks".equals(gateArgs[i])) {
                includeDocChecks = true;
            } else if ("--doctor".equals(gateArgs[i])) {
                doctor = true;
            } else if ("--baseline".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                baselinePath = Paths.get(gateArgs[++i]);
            } else if ("--output-json".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                jsonOutputPath = Paths.get(gateArgs[++i]).toAbsolutePath();
            } else if ("--output-sarif".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                sarifOutputPath = Paths.get(gateArgs[++i]).toAbsolutePath();
            } else if ("--publish-github-checks".equals(gateArgs[i])) {
                publishGithubChecks = true;
            } else if ("--publish-pr-comments".equals(gateArgs[i])) {
                publishPrComments = true;
            } else if ("--label-ai-slop".equals(gateArgs[i])) {
                publishAiSlopLabel = true;
                if (i + 1 < gateArgs.length && !gateArgs[i + 1].startsWith("-")) {
                    aiSlopLabelOverride = gateArgs[++i];
                }
            } else if ("--comment-ai-slop".equals(gateArgs[i])) {
                publishAiSlopComment = true;
            } else if ("--label-pr-on-advisory".equals(gateArgs[i])) {
                labelPrOnAdvisory = true;
                if (i + 1 < gateArgs.length && !gateArgs[i + 1].startsWith("-")) {
                    advisoryLabelOverride = gateArgs[++i];
                }
            } else if ("--warnings-exit-code".equals(gateArgs[i]) && hasValue(gateArgs, i)) {
                try {
                    warningsExitCode = Integer.parseInt(gateArgs[++i].trim());
                } catch (NumberFormatException ex) {
                    log.error("Invalid --warnings-exit-code (expected integer)");
                    return 2;
                }
            } else {
                log.error("Unknown option or missing value: {}", gateArgs[i]);
                return 2;
            }
        }

        try {
            DiffProvider diffProvider;
            if (diffJsonPath != null) {
                Path abs = diffJsonPath.isAbsolute()
                        ? diffJsonPath.normalize()
                        : workspace.resolve(diffJsonPath).toAbsolutePath().normalize();
                diffProvider = MemoryDiffProvider.fromJsonFile(abs);
            } else {
                diffProvider = new GitDiffProvider();
            }
            if (mechanicalFix) {
                diffProvider = applyMechanicalFix(workspace, baseRef, headRef, diffProvider);
            }
            ConfigProvider configProvider = new YamlConfigProvider();
            GitRefConfigProvider trustedConfigProvider = null;
            // Trust boundary: judge the PR with the config from the protected base ref, not the PR head.
            // Only meaningful for a real git diff (the in-memory --diff-json path has no base ref).
            if (!configFromWorkspace && diffJsonPath == null) {
                trustedConfigProvider = new GitRefConfigProvider(baseRef, configProvider);
                configProvider = trustedConfigProvider;
            }
            if (includeDocChecks) {
                configProvider = new DocChecksConfigProvider(configProvider, true);
            }
            if (baselinePath != null) {
                final Path baselineArg = baselinePath.isAbsolute()
                        ? baselinePath.normalize()
                        : workspace.resolve(baselinePath).toAbsolutePath().normalize();
                final ConfigProvider inner = configProvider;
                configProvider = ws -> {
                    AIVConfig base = inner.getConfig(ws);
                    Map<String, Object> global = new HashMap<>(base.getGlobalConfig());
                    global.put("baseline", baselineArg.toString());
                    return new AIVConfig(base.getGates(), global);
                };
            }
            // Resolve config once, freeze every policy sidecar, and make gates consume that same snapshot.
            AIVConfig resolvedConfig = configProvider.getConfig(workspace);
            boolean policyFallback = trustedConfigProvider != null && trustedConfigProvider.usedFallback();
            String pinnedPolicyCommit = trustedConfigProvider != null && !policyFallback
                    ? trustedConfigProvider.resolvedBaseCommit(workspace).orElse(null) : null;
            if (requireTrustedConfig && trustedConfigProvider == null) {
                log.error("--require-trusted-config needs a git diff and base-ref policy");
                return 2;
            }
            if (requireTrustedConfig && policyFallback) {
                log.error("Trusted policy unavailable at {}; workspace fallback refused", baseRef);
                return 2;
            }
            if (requireTrustedConfig && !trustedConfigProvider.policyTreeMatches(workspace)) {
                log.error("Policy files under .aiv differ from trusted ref {}; policy evaluation refused", baseRef);
                return 2;
            }
            Path policySnapshot = trustedConfigProvider != null && !policyFallback
                    ? trustedConfigProvider.snapshotPolicyTree(workspace)
                    : VerifyCommand.snapshotWorkspacePolicy(workspace);
            if (baselinePath != null) {
                Path sourceBaseline = baselinePath.isAbsolute() ? baselinePath.normalize()
                        : workspace.resolve(baselinePath).toAbsolutePath().normalize();
                Path frozenBaseline = policySnapshot.resolve(".aiv/cli-baseline.txt");
                Files.createDirectories(frozenBaseline.getParent());
                Files.copy(sourceBaseline, frozenBaseline, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Map<String, Object> global = new HashMap<>(resolvedConfig.getGlobalConfig());
                global.put("baseline", ".aiv/cli-baseline.txt");
                resolvedConfig = new AIVConfig(resolvedConfig.getGates(), global);
            }
            VerifyCommand.policyTreeMaterial(resolvedConfig, policySnapshot);
            if (requireTrustedConfig) {
                Map<String, Object> protectedGlobal = new HashMap<>(resolvedConfig.getGlobalConfig());
                protectedGlobal.put("protect_policy_paths", true);
                resolvedConfig = new AIVConfig(resolvedConfig.getGates(), protectedGlobal);
            }
            AIVConfig finalResolvedConfig = resolvedConfig;
            configProvider = ignored -> finalResolvedConfig;
            var recording = new RecordingReportPublisher(new StdoutReportPublisher(quiet));
            int coreExit;
            try {
                coreExit = new Orchestrator(diffProvider, configProvider, recording, policySnapshot)
                        .run(workspace, baseRef, headRef, doctor);
            } finally {
                VerifyCommand.deletePolicySnapshot(policySnapshot);
            }
            AIVResult last = recording.getLastResult();
            if (last != null && trustedConfigProvider != null && trustedConfigProvider.usedFallback()) {
                List<String> notices = new ArrayList<>(last.getNotices());
                notices.add("[POLICY-FALLBACK] trusted policy unavailable at " + baseRef + "; workspace policy used");
                last = new AIVResult(last.isPassed(), last.getGateResults(), notices);
                if (!quiet) {
                    System.out.println(notices.get(notices.size() - 1));
                }
            }
            if (last != null) {
                try {
                    // The audit log location comes from the same (base-ref) config as the verdict, so a PR cannot
                    // redirect or switch off its own record. CLI flag and AIV_AUDIT_LOG override for CI jobs.
                    String auditLog = firstNonBlank(auditLogArg, GITHUB_ENV.apply("AIV_AUDIT_LOG"),
                            configProvider.getConfig(workspace).getGlobalString("audit_log").orElse(null));
                    if (auditLog != null) {
                        Path logPath = Paths.get(auditLog);
                        logPath = logPath.isAbsolute() ? logPath : workspace.resolve(logPath).normalize();
                        String source = configFromWorkspace || diffJsonPath != null ? "workspace" : "base-ref";
                        last = EvidenceCommands.record(logPath, last, workspace, baseRef, headRef, coreExit, doctor,
                                cliVersion(), source, pinnedPolicyCommit);
                        if (!quiet) {
                            System.out.println(last.getNotices().get(last.getNotices().size() - 1));
                        }
                    }
                    if (jsonOutputPath != null) {
                        Path parent = jsonOutputPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        JsonReportWriter.write(last, jsonOutputPath, cliVersion());
                    }
                    if (sarifOutputPath != null) {
                        Path parent = sarifOutputPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        SarifReportWriter.write(last, sarifOutputPath, cliVersion());
                    }
                    if (publishGithubChecks) {
                        GithubChecksPublisher.publish(last, cliVersion(), GITHUB_ENV);
                    }
                    if (publishPrComments) {
                        GithubPrReviewCommentsPublisher.publish(last, GITHUB_ENV);
                    }
                    AIVConfig publicationConfig = configProvider.getConfig(workspace);
                    if (labelPrOnAdvisory || "true".equalsIgnoreCase(blankToEmpty(GITHUB_ENV.apply("AIV_LABEL_PR_ON_ADVISORY")))) {
                        publishAdvisoryPrLabel(publicationConfig, last, advisoryLabelOverride);
                    }
                    // Publication selection comes from the same trusted configuration as the verdict. A pull
                    // request cannot change which findings are labeled or commented by editing workspace config.
                    boolean github = inGithubContext(GITHUB_ENV);
                    boolean configAiSlopLabel = publicationConfig.getAiSlopPrLabel().isPresent();
                    boolean configAiSlopComment = publicationConfig.isAiSlopPrCommentEnabled();
                    if (!github && (configAiSlopLabel || configAiSlopComment) && !publishAiSlopLabel && !publishAiSlopComment) {
                        log.info("AI-SLOP label/comment configured but GITHUB_TOKEN/GITHUB_REPOSITORY are not set; publication skipped (local run)");
                    }
                    if (publishAiSlopLabel || (configAiSlopLabel && github)
                            || "true".equalsIgnoreCase(blankToEmpty(GITHUB_ENV.apply("AIV_LABEL_AI_SLOP")))) {
                        publishAiSlopLabel(last, publicationConfig, aiSlopLabelOverride);
                    }
                    if (publishAiSlopComment || (configAiSlopComment && github)
                            || "true".equalsIgnoreCase(blankToEmpty(GITHUB_ENV.apply("AIV_COMMENT_AI_SLOP")))) {
                        GithubPrDeterministicFindingsPublisher.publish(last,
                                publicationConfig.getAiSlopLabelGates(), publicationConfig.getAiSlopLabelRules(), GITHUB_ENV);
                    }
                } catch (IOException e) {
                    log.error("Could not write report: {}", e.getMessage());
                    return 2;
                } catch (IllegalStateException e) {
                    log.error("GitHub integration: {}", e.getMessage());
                    return 2;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("GitHub integration interrupted");
                    return 2;
                }
            }
            if (doctor) {
                return 0;
            }
            return mergeWarningsExit(coreExit, warningsExitCode, last);
        } catch (IllegalArgumentException e) {
            log.error("AIV error: {}", e.getMessage());
            return 2;
        } catch (IllegalStateException e) {
            log.error("AIV git error: {}", e.getMessage());
            return 3;
        } catch (Exception e) {
            log.error("AIV error: {}", e.getMessage());
            return 2;
        }
    }

    /**
     * Rewrites workspace files (conflict markers / elision lines), then overlays fixed contents
     * into an in-memory diff so the subsequent gate run sees the cleaned sources.
     */
    static DiffProvider applyMechanicalFix(Path workspace, String baseRef, String headRef, DiffProvider inner)
            throws IOException {
        Diff original = inner.getDiff(workspace, baseRef, headRef);
        List<String> paths = new ArrayList<>();
        for (ChangedFile f : original.getChangedFiles()) {
            paths.add(f.getPath());
        }
        int n = MechanicalFixer.apply(workspace, paths);
        if (n > 0) {
            log.info("Mechanical --fix rewrote {} file(s)", n);
        }
        List<ChangedFile> updated = new ArrayList<>();
        for (ChangedFile f : original.getChangedFiles()) {
            Path p = workspace.resolve(f.getPath());
            String content = f.getContent();
            if (Files.isRegularFile(p)) {
                content = Files.readString(p, StandardCharsets.UTF_8);
            }
            updated.add(new ChangedFile(f.getPath(), f.getChangeType(), content));
        }
        return new MemoryDiffProvider(new Diff(
                original.getBaseRef(), original.getHeadRef(), updated, original.getRawDiff(),
                original.getLinesAdded(), original.getLinesDeleted(), original.getAuthorEmail(),
                original.isHeadCommitSigned(), original.isSkipDirectivePresent(),
                original.getWarnings(), original.getPerFileNetLoc()));
    }

    /**
     * When the run passed but the diff carried warnings, optionally map exit to {@code warningsExitCode} for CI.
     */
    static int mergeWarningsExit(int coreExit, int warningsExitCode, AIVResult last) {
        if (coreExit == 0 && warningsExitCode > 0 && last != null && last.isPassed()
                && !last.getNotices().isEmpty()) {
            return warningsExitCode;
        }
        return coreExit;
    }

    static void publishAdvisoryPrLabel(AIVConfig cfg, AIVResult last, String labelOverride)
            throws IOException, InterruptedException {
        String label = firstNonBlank(
                labelOverride,
                GITHUB_ENV.apply("AIV_PR_ADVISORY_LABEL"),
                cfg.getAdvisoryPrLabel().orElse(null),
                GithubPrLabelPublisher.DEFAULT_LABEL);
        List<String> gates = cfg.getAdvisoryLabelGates();
        GithubPrLabelPublisher.apply(last, label, gates, GITHUB_ENV);
    }

    static void publishAiSlopLabel(AIVResult last, AIVConfig cfg, String labelOverride)
            throws IOException, InterruptedException {
        String label = firstNonBlank(labelOverride, GITHUB_ENV.apply("AIV_AI_SLOP_LABEL"),
                cfg.getAiSlopPrLabel().orElse(null), "AI-SLOP");
        GithubPrLabelPublisher.applyForSelectedFindings(last, label, cfg.getAiSlopLabelGates(),
                cfg.getAiSlopLabelRules(), GITHUB_ENV);
    }

    /** True when a token and an owner/repo are available, i.e. GitHub publication can be attempted. */
    static boolean inGithubContext(java.util.function.Function<String, String> env) {
        String token = env.apply("GITHUB_TOKEN");
        String repo = env.apply("GITHUB_REPOSITORY");
        return token != null && !token.isBlank() && repo != null && repo.contains("/");
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static boolean hasValue(String[] args, int index) {
        return index + 1 < args.length && !args[index + 1].startsWith("--");
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String[] tail(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] r = new String[args.length - 1];
        System.arraycopy(args, 1, r, 0, r.length);
        return r;
    }

    private static Path parseWorkspace(String[] args) {
        Path workspace = Paths.get(".").toAbsolutePath();
        for (int i = 0; i < args.length; i++) {
            if ("--workspace".equals(args[i]) && i + 1 < args.length) {
                workspace = Paths.get(args[++i]).toAbsolutePath();
            }
        }
        return workspace;
    }

    static String cliVersion() {
        return readVersionFromStream(Main.class.getClassLoader().getResourceAsStream("META-INF/aiv-cli.properties"));
    }

    static String readVersionFromStream(InputStream in) {
        try (InputStream stream = in) {
            if (stream == null) {
                return "dev";
            }
            Properties p = new Properties();
            p.load(stream);
            String v = p.getProperty("version", "").trim();
            return v.isEmpty() ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }
}
