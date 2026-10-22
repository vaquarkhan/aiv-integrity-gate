/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli.config;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Trust-boundary config provider: reads {@code .aiv/config.yaml} from the <em>base</em> ref
 * ({@code git show <base>:.aiv/config.yaml}) rather than from the pull-request head checkout.
 *
 * <p>Without this, a PR could weaken or disable the gate that judges it by editing the config in the
 * same change. The base ref is the protected branch, so the config there was reviewed before the PR.
 *
 * <p>Fallback: when the base ref has no {@code .aiv/config.yaml} (first adoption, or a shallow clone
 * without the ref), the workspace config is used and a warning is logged. {@link #usedFallback()}
 * exposes that so the CLI can surface it as a notice.
 */
public final class GitRefConfigProvider implements ConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(GitRefConfigProvider.class);

    /** Runs {@code git show <ref>:<path>} in {@code workspace}; empty when the object does not exist. */
    @FunctionalInterface
    public interface GitShow extends BiFunction<Path, String, Optional<String>> {
    }

    private final String baseRef;
    private final ConfigProvider workspaceFallback;
    private final YamlConfigProvider parser;
    private final GitShow gitShow;
    private volatile boolean usedFallback;
    private volatile boolean pinAttempted;
    private volatile String pinnedCommit;

    public GitRefConfigProvider(String baseRef, ConfigProvider workspaceFallback) {
        this(baseRef, workspaceFallback, GitRefConfigProvider::defaultGitShow);
    }

    GitRefConfigProvider(String baseRef, ConfigProvider workspaceFallback, GitShow gitShow) {
        this.baseRef = baseRef;
        this.workspaceFallback = workspaceFallback;
        this.parser = new YamlConfigProvider();
        this.gitShow = gitShow;
    }

    @Override
    public AIVConfig getConfig(Path workspace) {
        String immutableRef = pinBaseCommit(workspace).orElse(baseRef);
        Optional<String> content = gitShow.apply(workspace, immutableRef + ":.aiv/config.yaml");
        if (content.isEmpty()) {
            usedFallback = true;
            log.warn("No .aiv/config.yaml at base ref '{}'; using workspace config (not pinned)", baseRef);
            return workspaceFallback.getConfig(workspace);
        }
        log.info("Using .aiv/config.yaml pinned to base ref '{}'", baseRef);
        return parser.parse(content.get());
    }

    /** True when the last {@link #getConfig(Path)} fell back to the workspace config. */
    public boolean usedFallback() {
        return usedFallback;
    }

    public String getBaseRef() {
        return baseRef;
    }

    /** Resolved immutable base commit used to bind the complete policy tree into evidence. */
    public Optional<String> resolvedBaseCommit(Path workspace) {
        return pinBaseCommit(workspace);
    }

    private synchronized Optional<String> pinBaseCommit(Path workspace) {
        if (!pinAttempted) {
            pinAttempted = true;
            pinnedCommit = gitOutput(workspace, List.of("rev-parse", "--verify", baseRef + "^{commit}"))
                    .orElse(null);
        }
        return Optional.ofNullable(pinnedCommit);
    }

    /**
     * Compares the complete live .aiv tree (including ignored files) with the trusted Git tree.
     * Symlinks, missing/extra files, type changes, and content changes all fail the comparison.
     * Git hash-object applies the repository's normal clean filters, so CRLF checkout settings do not
     * create false drift.
     */
    public boolean policyTreeMatches(Path workspace) throws java.io.IOException {
        Optional<String> immutable = pinBaseCommit(workspace);
        if (immutable.isEmpty()) return false;
        Optional<String> tree = gitOutput(workspace, List.of("ls-tree", "-r", immutable.get(), "--", ".aiv"));
        if (tree.isEmpty()) return false;
        Map<String, String> expected = new LinkedHashMap<>();
        if (!tree.get().isBlank()) {
            for (String line : tree.get().split("\n")) {
                int tab = line.indexOf('\t');
                if (tab < 0) return false;
                String[] header = line.substring(0, tab).trim().split("\\s+");
                if (header.length != 3 || !"100644".equals(header[0]) && !"100755".equals(header[0])) return false;
                expected.put(line.substring(tab + 1).replace('\\', '/'), header[2]);
            }
        }

        Path root = workspace.toAbsolutePath().normalize().resolve(".aiv");
        if (!Files.exists(root)) return expected.isEmpty();
        if (Files.isSymbolicLink(root)) return false;
        TreeSet<String> actual = new TreeSet<>();
        List<Path> livePaths;
        try (var paths = Files.walk(root)) {
            livePaths = paths.toList();
        }
        for (Path path : livePaths) {
            if (Files.isSymbolicLink(path)) return false;
            if (Files.isRegularFile(path)) {
                actual.add(workspace.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/'));
            }
        }
        if (!actual.equals(new TreeSet<>(expected.keySet()))) return false;
        for (String relative : actual) {
            Optional<String> hash = gitOutput(workspace, List.of("hash-object", "--path=" + relative,
                    workspace.resolve(relative).toString()));
            if (hash.isEmpty() || !hash.get().equals(expected.get(relative))) return false;
        }
        return true;
    }

    /** Materialize the trusted commit's regular .aiv blobs into a private temporary policy root. */
    public Path snapshotPolicyTree(Path workspace) throws java.io.IOException {
        Optional<String> immutable = pinBaseCommit(workspace);
        if (immutable.isEmpty()) throw new java.io.IOException("cannot resolve trusted policy ref " + baseRef);
        Optional<String> tree = gitOutput(workspace, List.of("ls-tree", "-r", immutable.get(), "--", ".aiv"));
        if (tree.isEmpty()) throw new java.io.IOException("cannot read trusted .aiv tree at " + baseRef);
        Path snapshot = Files.createTempDirectory("aiv-policy-");
        if (tree.get().isBlank()) return snapshot;
        for (String line : tree.get().split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) throw new java.io.IOException("malformed git tree entry");
            String[] header = line.substring(0, tab).trim().split("\\s+");
            if (header.length != 3 || !"100644".equals(header[0]) && !"100755".equals(header[0])) throw new java.io.IOException("trusted policy tree contains a non-regular entry");
            String relative = line.substring(tab + 1).replace('\\', '/');
            if (!(relative.equals(".aiv") || relative.startsWith(".aiv/")) || relative.contains("../")) throw new java.io.IOException("trusted policy path escapes .aiv: " + relative);
            Optional<String> content = defaultGitShow(workspace, immutable.get() + ":" + relative);
            if (content.isEmpty()) throw new java.io.IOException("cannot read trusted policy blob " + relative);
            Path target = snapshot.resolve(relative).normalize();
            if (!target.startsWith(snapshot)) throw new java.io.IOException("trusted policy path escapes snapshot");
            Files.createDirectories(target.getParent());
            Files.writeString(target, content.get(), StandardCharsets.UTF_8);
        }
        return snapshot;
    }

    private static Optional<String> gitOutput(Path workspace, List<String> args) {
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add(gitCommand());
            command.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(command).directory(workspace.toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_PAGER", "cat");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return Optional.empty();
            }
            return Optional.of(new String(out, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<String> defaultGitShow(Path workspace, String spec) {
        try {
            ProcessBuilder pb = new ProcessBuilder(gitCommand(), "show", spec);
            pb.directory(workspace.toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_PAGER", "cat");
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            // readAllBytes returns when git closes stdout, i.e. at exit; waitFor then returns immediately.
            byte[] out = p.getInputStream().readAllBytes();
            int exit = p.waitFor();
            if (exit != 0) {
                return Optional.empty();
            }
            return Optional.of(new String(out, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // IOException (git missing), InterruptedException: either way the base config is unavailable.
            log.debug("git show failed", e);
            return Optional.empty();
        }
    }

    private static String gitCommand() {
        String override = System.getProperty("aiv.git.executable");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return "git";
    }
}
