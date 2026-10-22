/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli.config;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRefConfigProviderTest {

    private static final String BASE_CFG = """
            schema_version: 1
            gates:
              - id: syntax
                enabled: true
              - id: invariant
                enabled: true
            """;

    private static final String HEAD_CFG = """
            schema_version: 1
            gates:
              - id: syntax
                enabled: false
            """;

    @Test
    void prefersBaseRefConfigOverWorkspace(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve(".aiv"));
        Files.writeString(ws.resolve(".aiv/config.yaml"), HEAD_CFG, StandardCharsets.UTF_8);
        var p = new GitRefConfigProvider("origin/main", new YamlConfigProvider(),
                (dir, spec) -> "origin/main:.aiv/config.yaml".equals(spec) ? Optional.of(BASE_CFG) : Optional.empty());
        AIVConfig cfg = p.getConfig(ws);
        assertEquals(2, cfg.getGates().size());
        assertTrue(cfg.findGate("syntax").orElseThrow().isEnabled(), "PR head cannot switch the gate off");
        assertFalse(p.usedFallback());
        assertEquals("origin/main", p.getBaseRef());
    }

    @Test
    void fallsBackToWorkspaceWhenBaseHasNoConfig(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve(".aiv"));
        Files.writeString(ws.resolve(".aiv/config.yaml"), HEAD_CFG, StandardCharsets.UTF_8);
        var p = new GitRefConfigProvider("origin/main", new YamlConfigProvider(), (dir, spec) -> Optional.empty());
        AIVConfig cfg = p.getConfig(ws);
        assertEquals(1, cfg.getGates().size());
        assertTrue(p.usedFallback());
    }

    @Test
    void realGitShowReadsCommittedConfigAndIgnoresWorkingTree(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "t");
        git(repo, "config", "commit.gpgsign", "false");
        Files.createDirectories(repo.resolve(".aiv"));
        Files.writeString(repo.resolve(".aiv/config.yaml"), BASE_CFG, StandardCharsets.UTF_8);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "base");
        var clean = new GitRefConfigProvider("HEAD", new YamlConfigProvider());
        assertTrue(clean.policyTreeMatches(repo));
        Files.writeString(repo.resolve(".gitignore"), ".aiv/ignored.yaml\n", StandardCharsets.UTF_8);
        Files.writeString(repo.resolve(".aiv/ignored.yaml"), "rules: []\n", StandardCharsets.UTF_8);
        assertFalse(clean.policyTreeMatches(repo), "ignored policy files must still be detected");
        Files.delete(repo.resolve(".aiv/ignored.yaml"));
        Files.delete(repo.resolve(".gitignore"));
        // working tree now weakens the config; the committed base must still win
        Files.writeString(repo.resolve(".aiv/config.yaml"), HEAD_CFG, StandardCharsets.UTF_8);

        var p = new GitRefConfigProvider("HEAD", new YamlConfigProvider());
        assertTrue(p.resolvedBaseCommit(repo).isPresent());
        assertFalse(p.policyTreeMatches(repo), "working-tree policy drift must be detected");
        AIVConfig cfg = p.getConfig(repo);
        assertEquals(2, cfg.getGates().size());
        assertFalse(p.usedFallback());
        String pinned = p.resolvedBaseCommit(repo).orElseThrow();
        git(repo, "add", ".aiv/config.yaml");
        git(repo, "commit", "-q", "-m", "move symbolic HEAD");
        assertEquals(pinned, p.resolvedBaseCommit(repo).orElseThrow(), "base ref must stay pinned for the run");
        Path frozen = p.snapshotPolicyTree(repo);
        assertEquals(BASE_CFG, Files.readString(frozen.resolve(".aiv/config.yaml")));
        try (var paths = Files.walk(frozen)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }

        // object missing at ref -> empty -> fallback to workspace
        var missing = new GitRefConfigProvider("HEAD~5", new YamlConfigProvider());
        assertEquals(1, missing.getConfig(repo).getGates().size());
        assertTrue(missing.usedFallback());
        assertTrue(GitRefConfigProvider.defaultGitShow(repo, "HEAD:does/not/exist").isEmpty());
    }

    @Test
    void gitShowFailsClosedWhenGitCannotStart(@TempDir Path dir) throws Exception {
        String prev = System.getProperty("aiv.git.executable");
        try {
            String missing = dir.resolve("missing-git-" + System.nanoTime()).toString();
            System.setProperty("aiv.git.executable", missing);
            assertTrue(GitRefConfigProvider.defaultGitShow(dir, "HEAD:.aiv/config.yaml").isEmpty());
            GitRefConfigProvider provider = new GitRefConfigProvider("HEAD", new YamlConfigProvider());
            assertTrue(provider.resolvedBaseCommit(dir).isEmpty());
            assertFalse(provider.policyTreeMatches(dir));
        } finally {
            if (prev == null) {
                System.clearProperty("aiv.git.executable");
            } else {
                System.setProperty("aiv.git.executable", prev);
            }
        }
    }

    @Test
    void yamlProviderParsesStringsAndRejectsUnreadableFile(@TempDir Path ws) throws Exception {
        var yaml = new YamlConfigProvider();
        assertEquals(2, yaml.parse(BASE_CFG).getGates().size());
        assertThrows(IllegalArgumentException.class, () -> yaml.parse("schema_version: nope"));
        // a directory where the file should be: exists, but cannot be read as a file
        Files.createDirectories(ws.resolve(".aiv/config.yaml"));
        assertThrows(IllegalArgumentException.class, () -> yaml.getConfig(ws));
    }

    @Test
    void defaultConfigShipsHardGatesOnAndSoftGatesAdvisory(@TempDir Path empty) {
        AIVConfig cfg = new YamlConfigProvider().getConfig(empty);
        for (String hard : List.of("syntax", "invariant", "dependency", "package-existence", "security", "test-adequacy")) {
            AIVConfig.GateConfig g = cfg.findGate(hard).orElseThrow();
            assertTrue(g.isEnabled(), hard);
            assertEquals("fail", g.getSeverity(), hard);
        }
        for (String soft : List.of("density", "cohesion", "duplication")) {
            AIVConfig.GateConfig g = cfg.findGate(soft).orElseThrow();
            assertTrue(g.isEnabled(), soft);
            assertEquals("warn", g.getSeverity(), soft);
        }
        assertEquals(Map.of("fail_fast", false, "schema_version", 1), cfg.getGlobalConfig());
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), String.join(" ", cmd));
    }
}
