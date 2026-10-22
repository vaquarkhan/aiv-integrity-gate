/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.adapter.git.MemoryDiffProvider;
import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.cli.config.GitRefConfigProvider;
import io.github.vaquarkhan.aiv.cli.config.YamlConfigProvider;
import io.github.vaquarkhan.aiv.core.Orchestrator;
import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import io.github.vaquarkhan.aiv.port.DiffProvider;
import io.github.vaquarkhan.aiv.port.ReportPublisher;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code aiv verify}: language-neutral agent entry point. Reads one strict JSON request and writes one
 * JSON verdict to stdout. Diagnostics go to stderr. The evidence digest binds the request and resolved
 * policy. The response declares external inputs whenever enabled gates depend on registries, tools, or a
 * decision model, so callers can distinguish a local reproducible run from an environment-dependent one.
 *
 * @author Vaquar Khan
 */
final class VerifyCommand {

    static final int SCHEMA_VERSION = 2;

    private VerifyCommand() {
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Path requestFile = null;
        Path auditLog = null;
        Path workspaceArg = null;
        String policyRefArg = null;
        boolean requireTrustedPolicy = false;
        boolean capabilities = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--request" -> {
                    if (!hasValue(args, i)) return error(out, "--request requires a value");
                    requestFile = Paths.get(args[++i]);
                }
                case "--audit-log" -> {
                    if (!hasValue(args, i)) return error(out, "--audit-log requires a value");
                    auditLog = Paths.get(args[++i]);
                }
                case "--workspace" -> {
                    if (!hasValue(args, i)) return error(out, "--workspace requires a value");
                    workspaceArg = Paths.get(args[++i]).toAbsolutePath();
                }
                case "--policy-ref" -> {
                    if (!hasValue(args, i)) return error(out, "--policy-ref requires a value");
                    policyRefArg = args[++i];
                }
                case "--require-trusted-policy" -> requireTrustedPolicy = true;
                case "--capabilities" -> capabilities = true;
                default -> { return error(out, "unknown option: " + args[i]); }
            }
        }

        if (capabilities) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("schema_version", (long) SCHEMA_VERSION);
            response.put("status", "ok");
            response.put("capabilities", List.of("strict-options", "trusted-policy", "policy-tree-digest",
                    "external-input-disclosure", "audit-chain"));
            out.println(JsonLite.write(response));
            return 0;
        }

        String requestText;
        try {
            requestText = requestFile != null
                    ? Files.readString(requestFile, StandardCharsets.UTF_8)
                    : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return error(out, "cannot read request: " + e.getMessage());
        }

        Object request;
        try {
            request = JsonLite.parse(requestText);
        } catch (RuntimeException e) {
            return error(out, "invalid JSON request: " + e.getMessage());
        }

        Object artifact = JsonLite.get(request, "artifact");
        String artifactJson = artifact != null ? JsonLite.write(artifact) : requestText;
        String policyRef = policyRefArg != null ? policyRefArg : JsonLite.str(JsonLite.get(request, "policyRef"));
        Path workspace = workspaceArg;
        if (workspace == null) {
            String ctxWs = JsonLite.str(JsonLite.get(request, "context", "workspace"));
            workspace = (ctxWs != null ? Paths.get(ctxWs) : Paths.get(".")).toAbsolutePath();
        }

        try {
            Diff diff = MemoryDiffProvider.parseJson(artifactJson);
            DiffProvider diffProvider = new MemoryDiffProvider(diff);
            ConfigProvider workspaceProvider = new YamlConfigProvider();
            GitRefConfigProvider trustedProvider = null;
            ConfigProvider selectedProvider = workspaceProvider;
            String baseRef = "memory";
            if (policyRef != null && !policyRef.isBlank()) {
                trustedProvider = new GitRefConfigProvider(policyRef, workspaceProvider);
                selectedProvider = trustedProvider;
                baseRef = policyRef;
            }

            // Resolve once, then run and publish against that exact immutable configuration snapshot.
            AIVConfig resolvedConfig = selectedProvider.getConfig(workspace);
            boolean fallback = trustedProvider != null && trustedProvider.usedFallback();
            String policyCommit = trustedProvider == null ? null
                    : trustedProvider.resolvedBaseCommit(workspace).orElse(null);
            if (requireTrustedPolicy && trustedProvider == null) {
                return error(out, "--require-trusted-policy needs --policy-ref");
            }
            if (requireTrustedPolicy && (fallback || policyCommit == null
                    || !trustedProvider.policyTreeMatches(workspace))) {
                return error(out, "trusted policy unavailable or .aiv policy tree differs from " + policyRef
                        + "; workspace policy refused");
            }
            Path policySnapshot = trustedProvider != null && !fallback
                    ? trustedProvider.snapshotPolicyTree(workspace) : snapshotWorkspacePolicy(workspace);
            String policySidecars = policyTreeMaterial(resolvedConfig, policySnapshot);
            ConfigProvider resolvedProvider = ignored -> resolvedConfig;

            ReportPublisher silent = result -> { /* stdout is reserved for verdict JSON */ };
            RecordingReportPublisher recording = new RecordingReportPublisher(silent);
            int exit;
            try {
                exit = new Orchestrator(diffProvider, resolvedProvider, recording, policySnapshot)
                        .run(workspace, baseRef, "proposed", false);
            } finally {
                deletePolicySnapshot(policySnapshot);
            }

            AIVResult rawResult = recording.getLastResult();
            List<String> notices = new ArrayList<>(rawResult.getNotices());
            String policySource = trustedProvider == null ? "workspace" : fallback ? "workspace-fallback" : "trusted-ref";
            if (fallback) {
                notices.add("[POLICY-FALLBACK] trusted policy unavailable at " + policyRef + "; workspace policy used");
            }
            List<String> externalInputs = externalInputs(resolvedConfig);
            if (!externalInputs.isEmpty()) {
                notices.add("[EXTERNAL-INPUTS] " + String.join(", ", externalInputs));
            }
            AIVResult result = new AIVResult(rawResult.isPassed(), rawResult.getGateResults(), notices);

            String canonicalPolicy = JsonLite.write(canonicalPolicy(resolvedConfig)) + "\u0000"
                    + policySidecars + "\u0000" + (policyCommit == null ? "workspace" : policyCommit);
            String policyDigest = "sha256:" + HashChainAudit.sha256Hex(canonicalPolicy);
            String evidenceDigest = "sha256:" + HashChainAudit.sha256Hex(requestText + "\u0000" + canonicalPolicy);
            String verdict = result.isPassed() ? "pass" : "block";

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("schema_version", (long) SCHEMA_VERSION);
            response.put("aiv_cli_version", Main.cliVersion());
            response.put("verdict", verdict);
            response.put("blocking", !result.isPassed());
            response.put("passed", result.isPassed());
            response.put("advisory", result.hasAdvisoryFailures());
            response.put("gates", gatesJson(result));
            response.put("notices", notices);
            response.put("evidence_digest", evidenceDigest);
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("source", policySource);
            policy.put("ref", policyRef);
            policy.put("commit", policyCommit);
            policy.put("fallback", fallback);
            policy.put("digest", policyDigest);
            response.put("policy", policy);
            response.put("reproducible_local", externalInputs.isEmpty());
            response.put("external_inputs", externalInputs);

            if (auditLog != null) {
                Path logPath = auditLog.isAbsolute() ? auditLog : workspace.resolve(auditLog).normalize();
                String head = HashChainAudit.append(logPath,
                        auditPayload(verdict, result, evidenceDigest, policyDigest, policySource, externalInputs));
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("record_hash", head);
                audit.put("log", logPath.toString().replace('\\', '/'));
                response.put("audit", audit);
            }

            out.println(JsonLite.write(response));
            return exit;
        } catch (Exception e) {
            return error(out, "verify error: " + e.getMessage());
        }
    }

    private static List<String> externalInputs(AIVConfig config) {
        List<String> inputs = new ArrayList<>();
        if (enabled(config, "package-existence", true)) inputs.add("package-registry");
        if (enabled(config, "external", false)) inputs.add("external-tool");
        if (enabled(config, "jev", false)) inputs.add("decision-model");
        return inputs;
    }

    private static boolean enabled(AIVConfig config, String id, boolean defaultValue) {
        return config.findGate(id).map(AIVConfig.GateConfig::isEnabled).orElse(defaultValue);
    }

    private static Map<String, Object> canonicalPolicy(AIVConfig config) {
        Map<String, Object> root = new TreeMap<>();
        root.put("global", canonical(config.getGlobalConfig()));
        List<Object> gates = new ArrayList<>();
        for (AIVConfig.GateConfig gate : config.getGates()) {
            Map<String, Object> item = new TreeMap<>();
            item.put("config", canonical(gate.getConfig()));
            item.put("enabled", gate.isEnabled());
            item.put("id", gate.getId());
            item.put("severity", gate.getSeverity());
            gates.add(item);
        }
        root.put("gates", gates);
        return root;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> list = new ArrayList<>();
            for (Object item : iterable) list.add(canonical(item));
            return list;
        }
        return value;
    }

    /** Freeze the current workspace policy tree for one internally consistent workspace-mode run. */
    static Path snapshotWorkspacePolicy(Path workspace) throws IOException {
        Path snapshot = Files.createTempDirectory("aiv-policy-");
        Path sourceRoot = workspace.toAbsolutePath().normalize().resolve(".aiv");
        if (!Files.exists(sourceRoot)) return snapshot;
        if (Files.isSymbolicLink(sourceRoot)) throw new IllegalArgumentException(".aiv policy root must not be a symbolic link");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.sorted().toList()) {
                if (Files.isSymbolicLink(source)) throw new IllegalArgumentException("policy sidecars must not be symbolic links: " + source);
                Path relative = sourceRoot.relativize(source);
                Path target = snapshot.resolve(".aiv").resolve(relative).normalize();
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else if (Files.isRegularFile(source)) {
                    Files.createDirectories(target.getParent());
                    Files.write(target, Files.readAllBytes(source));
                }
            }
        }
        return snapshot;
    }

    static boolean FORCE_SNAPSHOT_DELETE_FAILURE = false;

    static void deletePolicySnapshot(Path snapshot) {
        if (snapshot == null || !Files.exists(snapshot)) return;
        try {
            if (FORCE_SNAPSHOT_DELETE_FAILURE) throw new IOException("forced");
            try (var paths = Files.walk(snapshot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { path.toFile().deleteOnExit(); }
                }
            }
        } catch (IOException ignored) {
            snapshot.toFile().deleteOnExit();
        }
    }

    /**
     * Captures every policy sidecar under .aiv and rejects configured policy references outside that
     * tree. This keeps workspace and trusted-ref evidence complete and prevents path/symlink escapes.
     */
    static String policyTreeMaterial(AIVConfig config, Path workspace) throws IOException {
        List<String> references = new ArrayList<>();
        config.getGlobalString("baseline").ifPresent(references::add);
        config.findGate("design").map(AIVConfig.GateConfig::getConfig)
                .map(c -> c.get("rules_path")).filter(v -> v != null).map(Object::toString)
                .ifPresent(references::add);
        config.findGate("doc-integrity").map(AIVConfig.GateConfig::getConfig)
                .map(c -> c.get("rules_path")).filter(v -> v != null).map(Object::toString)
                .ifPresent(references::add);
        config.findGate("rules").map(AIVConfig.GateConfig::getConfig).ifPresent(c -> {
            Object files = c.get("rules_files");
            if (files instanceof Iterable<?> list) {
                for (Object file : list) if (file != null) references.add(file.toString());
            }
        });
        for (String reference : references) validatePolicyReference(reference);

        Path workspaceRoot = workspace.toAbsolutePath().normalize();
        Path policyRoot = workspaceRoot.resolve(".aiv").normalize();
        if (!Files.exists(policyRoot)) return "";
        if (Files.isSymbolicLink(policyRoot)) throw new IllegalArgumentException(".aiv policy root must not be a symbolic link");
        Path realWorkspace = workspaceRoot.toRealPath();
        Path realPolicyRoot = policyRoot.toRealPath();
        if (!realPolicyRoot.startsWith(realWorkspace)) throw new IllegalArgumentException(".aiv policy root escapes the workspace");
        StringBuilder material = new StringBuilder();
        try (var paths = Files.walk(realPolicyRoot)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("policy sidecars must not be symbolic links: " + path);
                if (!Files.isRegularFile(path)) continue;
                if (!path.toRealPath().startsWith(realPolicyRoot)) throw new IllegalArgumentException("policy sidecar escapes .aiv: " + path);
                String relative = workspaceRoot.relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                material.append(relative).append('\u0000')
                        .append(Base64.getEncoder().encodeToString(Files.readAllBytes(path))).append('\u0000');
            }
        }
        return material.toString();
    }

    private static void validatePolicyReference(String raw) {
        String reference = raw == null ? "" : raw.trim().replace('\\', '/');
        boolean absolute = reference.startsWith("/") || reference.matches("^[A-Za-z]:.*");
        boolean traversal = List.of(reference.split("/", -1)).contains("..");
        if (absolute || traversal || !(reference.equals(".aiv") || reference.startsWith(".aiv/"))) {
            throw new IllegalArgumentException("policy sidecar must stay under .aiv/: " + raw);
        }
    }

    private static List<Object> gatesJson(AIVResult result) {
        List<Object> gates = new ArrayList<>();
        for (GateResult g : result.getGateResults()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getGateId());
            gm.put("passed", g.isPassed());
            gm.put("blocks_ci", g.blocksCi());
            gm.put("message", g.getMessage());
            List<Object> findings = new ArrayList<>();
            for (Finding f : g.getFindings()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("rule_id", f.getRuleId());
                fm.put("file", f.getFilePath());
                fm.put("start_line", (long) f.getStartLine());
                fm.put("start_column", f.getStartColumn() == null ? null : f.getStartColumn().longValue());
                fm.put("end_line", f.getEndLine() == null ? null : f.getEndLine().longValue());
                fm.put("end_column", f.getEndColumn() == null ? null : f.getEndColumn().longValue());
                fm.put("message", f.getMessage());
                findings.add(fm);
            }
            gm.put("findings", findings);
            gates.add(gm);
        }
        return gates;
    }

    private static String auditPayload(String verdict, AIVResult result, String evidenceDigest,
                                       String policyDigest, String policySource, List<String> externalInputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "verify");
        payload.put("verdict", verdict);
        payload.put("passed", result.isPassed());
        payload.put("evidence_digest", evidenceDigest);
        payload.put("policy_digest", policyDigest);
        payload.put("policy_source", policySource);
        payload.put("external_inputs", externalInputs);
        List<Object> gates = new ArrayList<>();
        for (GateResult g : result.getGateResults()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("id", g.getGateId());
            gm.put("passed", g.isPassed());
            gm.put("blocks_ci", g.blocksCi());
            gates.add(gm);
        }
        payload.put("gates", gates);
        return JsonLite.write(payload);
    }

    private static boolean hasValue(String[] args, int index) {
        return index + 1 < args.length && !args[index + 1].startsWith("--");
    }

    private static int error(PrintStream out, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schema_version", (long) SCHEMA_VERSION);
        response.put("error", message);
        out.println(JsonLite.write(response));
        return 2;
    }
}
