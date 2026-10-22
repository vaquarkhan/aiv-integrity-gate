/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.util.JsonLite;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Vaquar Khan
 */
class VerifyCommandTest {

    private static final String REQUEST = "{\n"
            + "  \"artifact\": {\n"
            + "    \"baseRef\": \"memory\", \"headRef\": \"proposed\", \"rawDiff\": \"\",\n"
            + "    \"files\": [ { \"path\": \"src/A.java\", \"changeType\": \"MODIFIED\", \"content\": \"class A {}\" } ]\n"
            + "  },\n"
            + "  \"context\": { \"workspace\": \".\" }\n"
            + "}";

    private static Map<String, Object> runVerify(String[] args, String stdin, int[] exitOut) {
        InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        exitOut[0] = VerifyCommand.run(args, in, ps, ps);
        Object parsed = JsonLite.parse(out.toString(StandardCharsets.UTF_8));
        assertTrue(parsed instanceof Map, "verify must emit a single JSON object on stdout");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        return m;
    }

    @Test
    void capabilityHandshakeSucceedsWithoutARequest() {
        int[] exit = new int[1];
        Map<String, Object> result = runVerify(new String[]{"--capabilities"}, "", exit);
        assertEquals(0, exit[0]);
        assertEquals("ok", result.get("status"));
        @SuppressWarnings("unchecked")
        List<Object> capabilities = (List<Object>) result.get("capabilities");
        assertTrue(capabilities.contains("trusted-policy"));
    }

    @Test
    void emitsStructuredVerdictWithEvidenceDigest(@TempDir Path tmp) {
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(new String[]{"--workspace", tmp.toString()}, REQUEST, exit);

        assertEquals(2L, r.get("schema_version"));
        assertNotNull(r.get("policy"));
        assertNotNull(r.get("external_inputs"));
        String verdict = JsonLite.str(r.get("verdict"));
        assertTrue("pass".equals(verdict) || "block".equals(verdict), "verdict must be pass or block, was " + verdict);
        assertNotNull(r.get("gates"));
        assertNotNull(r.get("notices"));
        String digest = JsonLite.str(r.get("evidence_digest"));
        assertNotNull(digest);
        assertTrue(digest.startsWith("sha256:"), "evidence digest must be a sha256 of the request");
    }

    @Test
    void verdictAndDigestAreDeterministic(@TempDir Path tmp) {
        int[] e1 = new int[1];
        int[] e2 = new int[1];
        Map<String, Object> r1 = runVerify(new String[]{"--workspace", tmp.toString()}, REQUEST, e1);
        Map<String, Object> r2 = runVerify(new String[]{"--workspace", tmp.toString()}, REQUEST, e2);

        assertEquals(e1[0], e2[0], "same request must yield the same exit code");
        assertEquals(r1.get("verdict"), r2.get("verdict"), "same request must yield the same verdict");
        assertEquals(r1.get("evidence_digest"), r2.get("evidence_digest"),
                "same request must yield the same evidence digest");
    }

    @Test
    void auditLogRecordsVerdictAndChainVerifies(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("verify-audit.log");
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(
                new String[]{"--workspace", tmp.toString(), "--audit-log", log.toString()}, REQUEST, exit);

        Object audit = r.get("audit");
        assertTrue(audit instanceof Map, "audit object must be present when --audit-log is given");
        @SuppressWarnings("unchecked")
        Map<String, Object> am = (Map<String, Object>) audit;
        String recordHash = JsonLite.str(am.get("record_hash"));
        assertNotNull(recordHash);
        assertTrue(Files.exists(log), "audit log file must be written");
        assertTrue(HashChainAudit.verify(log, recordHash).isIntact(),
                "the recorded chain must verify against its returned head hash");
    }

    @Test
    void trustedPolicyAcceptsCleanPolicyTreeAndBindsCommit(@TempDir Path repo) throws Exception {
        runGit(repo, "init", "-q");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test");
        Files.createDirectories(repo.resolve(".aiv"));
        Files.writeString(repo.resolve(".aiv/config.yaml"), "schema_version: 1\ngates: []\n",
                StandardCharsets.UTF_8);
        runGit(repo, "add", ".aiv/config.yaml");
        runGit(repo, "commit", "-q", "-m", "policy");
        int[] exit = new int[1];
        Map<String, Object> result = runVerify(new String[]{"--workspace", repo.toString(),
                "--policy-ref", "HEAD", "--require-trusted-policy"}, REQUEST, exit);
        assertTrue(exit[0] == 0 || exit[0] == 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) result.get("policy");
        assertEquals("trusted-ref", policy.get("source"));
        assertNotNull(policy.get("commit"));
    }

    private static void runGit(Path directory, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
    }

    @Test
    void invalidJsonReturnsStructuredError(@TempDir Path tmp) {
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(new String[]{"--workspace", tmp.toString()}, "{ not json", exit);
        assertEquals(2, exit[0]);
        assertNotNull(r.get("error"));
    }

    @Test
    void readsWorkspaceFromContextWhenNoFlagAndNoArtifactWrapper(@TempDir Path tmp) {
        String ws = tmp.toString().replace('\\', '/');
        String request = "{ \"files\": [ { \"path\": \"src/A.java\", \"changeType\": \"MODIFIED\","
                + " \"content\": \"class A {}\" } ], \"context\": { \"workspace\": \"" + ws + "\" } }";
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(new String[0], request, exit);
        assertNotNull(r.get("verdict"), "verdict must be produced using the workspace from context");
    }

    @Test
    void readsRequestFromFile(@TempDir Path tmp) throws Exception {
        Path reqFile = tmp.resolve("request.json");
        Files.writeString(reqFile, REQUEST, StandardCharsets.UTF_8);
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(
                new String[]{"--workspace", tmp.toString(), "--request", reqFile.toString()}, "", exit);
        assertNotNull(r.get("verdict"), "verdict must be produced from a --request file");
    }

    @Test
    void requireTrustedPolicyNeedsPolicyRef(@TempDir Path tmp) {
        int[] exit = new int[1];
        Map<String, Object> result = runVerify(new String[]{"--workspace", tmp.toString(),
                "--require-trusted-policy"}, REQUEST, exit);
        assertEquals(2, exit[0]);
        assertTrue(JsonLite.str(result.get("error")).contains("needs --policy-ref"));
    }

    @Test
    void requireTrustedPolicyFailsClosedWhenRefUnavailable(@TempDir Path tmp) {
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(new String[]{"--workspace", tmp.toString(), "--policy-ref", "HEAD",
                "--require-trusted-policy"}, REQUEST, exit);
        assertEquals(2, exit[0]);
        assertTrue(JsonLite.str(r.get("error")).contains("workspace policy refused"));
    }

    @Test
    void policySidecarsAreHashedAndCannotEscapeAiv(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".aiv/rules"));
        Files.writeString(tmp.resolve(".aiv/design.yaml"), "constraints: []\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve(".aiv/doc.yaml"), "doc_constraints: []\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve(".aiv/rules/team.yaml"), "rules: []\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve(".aiv/baseline.txt"), "", StandardCharsets.UTF_8);
        AIVConfig config = new AIVConfig(List.of(
                new AIVConfig.GateConfig("design", true, Map.of("rules_path", ".aiv/design.yaml")),
                new AIVConfig.GateConfig("doc-integrity", true, Map.of("rules_path", ".aiv/doc.yaml")),
                new AIVConfig.GateConfig("rules", true, Map.of("rules_files", List.of(".aiv/rules/*.yaml")))) ,
                Map.of("baseline", ".aiv/baseline.txt"));
        String first = VerifyCommand.policyTreeMaterial(config, tmp);
        Files.writeString(tmp.resolve(".aiv/rules/team.yaml"), "rules: [changed]\n", StandardCharsets.UTF_8);
        String second = VerifyCommand.policyTreeMaterial(config, tmp);
        assertTrue(!first.equals(second), "exact sidecar bytes must be bound into policy evidence");

        for (AIVConfig invalid : List.of(
                new AIVConfig(List.of(), Map.of("baseline", "../baseline.txt")),
                new AIVConfig(List.of(new AIVConfig.GateConfig("design", true,
                        Map.of("rules_path", "C:/outside.yaml"))), Map.of()),
                new AIVConfig(List.of(new AIVConfig.GateConfig("rules", true,
                        Map.of("rules_files", List.of("rules/*.yaml")))), Map.of()))) {
            assertThrows(IllegalArgumentException.class,
                    () -> VerifyCommand.policyTreeMaterial(invalid, tmp));
        }
    }

    @Test
    void workspacePolicySnapshotIsImmutableForTheRun(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".aiv"));
        Files.writeString(tmp.resolve(".aiv/config.yaml"), "schema_version: 1\n", StandardCharsets.UTF_8);
        Path snapshot = VerifyCommand.snapshotWorkspacePolicy(tmp);
        try {
            String before = VerifyCommand.policyTreeMaterial(new AIVConfig(List.of(), Map.of()), snapshot);
            Files.writeString(tmp.resolve(".aiv/config.yaml"), "schema_version: 2\n", StandardCharsets.UTF_8);
            String after = VerifyCommand.policyTreeMaterial(new AIVConfig(List.of(), Map.of()), snapshot);
            assertEquals(before, after);
        } finally {
            VerifyCommand.deletePolicySnapshot(snapshot);
        }
        assertTrue(Files.notExists(snapshot));
        Path emptySnapshot = VerifyCommand.snapshotWorkspacePolicy(tmp.resolve("empty"));
        VerifyCommand.deletePolicySnapshot(emptySnapshot);
        Path forced = Files.createTempDirectory("forced-policy-delete-");
        try {
            VerifyCommand.FORCE_SNAPSHOT_DELETE_FAILURE = true;
            VerifyCommand.deletePolicySnapshot(forced);
            assertTrue(Files.exists(forced));
        } finally {
            VerifyCommand.FORCE_SNAPSHOT_DELETE_FAILURE = false;
            Files.deleteIfExists(forced);
        }
    }

    @Test
    void policySidecarSymlinkOutsideAivIsRejected(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".aiv"));
        Path outside = tmp.resolve("outside.yaml");
        Files.writeString(outside, "rules: []\n", StandardCharsets.UTF_8);
        Path link = tmp.resolve(".aiv/link.yaml");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic links unavailable in this test environment");
        }
        assertThrows(IllegalArgumentException.class,
                () -> VerifyCommand.policyTreeMaterial(new AIVConfig(List.of(), Map.of()), tmp));
    }

    @Test
    void policyContentChangesEvidenceDigestAndProfileIsDisclosed(@TempDir Path tmp) throws Exception {
        Path configDir = tmp.resolve(".aiv");
        Files.createDirectories(configDir);
        Path config = configDir.resolve("config.yaml");
        Files.writeString(config, """
                schema_version: 1
                exclude_paths: [build/**]
                gates:
                  - id: package-existence
                    enabled: false
                    config: {allow: [internal-sdk]}
                  - id: external
                    enabled: false
                  - id: jev
                    enabled: false
                """, StandardCharsets.UTF_8);
        int[] firstExit = new int[1];
        Map<String, Object> first = runVerify(new String[]{"--workspace", tmp.toString()}, REQUEST, firstExit);
        Files.writeString(config, Files.readString(config) + "fail_fast: true\n", StandardCharsets.UTF_8);
        int[] secondExit = new int[1];
        Map<String, Object> second = runVerify(new String[]{"--workspace", tmp.toString()}, REQUEST, secondExit);
        assertTrue((Boolean) first.get("reproducible_local"));
        assertEquals(List.of(), first.get("external_inputs"));
        assertTrue(!first.get("evidence_digest").equals(second.get("evidence_digest")),
                "resolved policy must be bound into the evidence digest");
    }

    @Test
    void disclosesConfiguredExternalToolAndModelInputs(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".aiv"));
        Files.writeString(tmp.resolve(".aiv/config.yaml"), """
                schema_version: 1
                gates:
                  - {id: package-existence, enabled: true}
                  - {id: external, enabled: true}
                  - {id: jev, enabled: true}
                """, StandardCharsets.UTF_8);
        int[] exit = new int[1];
        Map<String, Object> result = runVerify(new String[]{"--workspace", tmp.toString()}, REQUEST, exit);
        @SuppressWarnings("unchecked")
        List<Object> inputs = (List<Object>) result.get("external_inputs");
        assertTrue(inputs.contains("package-registry"));
        assertTrue(inputs.contains("external-tool"));
        assertTrue(inputs.contains("decision-model"));
        assertEquals(false, result.get("reproducible_local"));
    }

    @Test
    void policyRefIsAcceptedAndProducesStructuredOutput(@TempDir Path tmp) {
        int[] exit = new int[1];
        // tmp is not a git repo, so the policy ref either falls back or errors; either way the policy-ref
        // branch executes and the output is a single JSON object (verdict or error).
        Map<String, Object> r = runVerify(
                new String[]{"--workspace", tmp.toString(), "--policy-ref", "HEAD"}, REQUEST, exit);
        assertTrue(r.get("verdict") != null || r.get("error") != null,
                "policy-ref run must produce a verdict or a structured error");
        if (r.get("verdict") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> policy = (Map<String, Object>) r.get("policy");
            assertEquals(true, policy.get("fallback"));
            assertEquals("workspace-fallback", policy.get("source"));
        }
    }

    @Test
    void auditAppendFailureReturnsStructuredError(@TempDir Path tmp) throws Exception {
        // Make the audit log's parent a regular file so directory creation (and thus append) fails.
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);
        Path log = blocker.resolve("sub.log");
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(
                new String[]{"--workspace", tmp.toString(), "--audit-log", log.toString()}, REQUEST, exit);
        assertEquals(2, exit[0]);
        assertNotNull(r.get("error"));
    }

    @Test
    void missingRequestFileReturnsStructuredError(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.json");
        int[] exit = new int[1];
        Map<String, Object> r = runVerify(
                new String[]{"--workspace", tmp.toString(), "--request", missing.toString()}, "", exit);
        assertEquals(2, exit[0]);
        assertNotNull(r.get("error"));
    }

    @Test
    void mainDispatchesVerifySubcommand(@TempDir Path tmp) throws Exception {
        Path reqFile = tmp.resolve("req.json");
        Files.writeString(reqFile, REQUEST, StandardCharsets.UTF_8);
        PrintStream prevOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            Main.run(new String[]{"verify", "--workspace", tmp.toString(), "--request", reqFile.toString()});
        } finally {
            System.setOut(prevOut);
        }
        Object parsed = JsonLite.parse(out.toString(StandardCharsets.UTF_8));
        assertTrue(parsed instanceof Map, "Main must route 'verify' to VerifyCommand and emit JSON");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) parsed;
        assertNotNull(r.get("verdict"));
    }

    @Test
    void unknownAndMissingOptionsFailClosed(@TempDir Path tmp) {
        int[] exit = new int[1];
        Map<String, Object> unknown = runVerify(
                new String[]{"--workspace", tmp.toString(), "--bogus"}, REQUEST, exit);
        assertEquals(2, exit[0]);
        assertTrue(JsonLite.str(unknown.get("error")).contains("unknown option"));
        Map<String, Object> chained = runVerify(
                new String[]{"--policy-ref", "--require-trusted-policy"}, REQUEST, exit);
        assertEquals(2, exit[0]);
        assertTrue(JsonLite.str(chained.get("error")).contains("requires a value"));
        for (String option : List.of("--request", "--audit-log", "--workspace", "--policy-ref")) {
            Map<String, Object> missing = runVerify(new String[]{option}, REQUEST, exit);
            assertEquals(2, exit[0]);
            assertTrue(JsonLite.str(missing.get("error")).contains("requires a value"));
        }
    }
}
