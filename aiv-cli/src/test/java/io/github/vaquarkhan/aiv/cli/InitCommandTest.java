/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitCommandTest {

    @Test
    void detectsJavaAndUsesHigherEntropyThreshold(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("App.java"), "class App {}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("Widget.jsx"), "export {}\n", StandardCharsets.UTF_8);
        assertEquals(0, InitCommand.run(root));
        String cfg = Files.readString(root.resolve(".aiv/config.yaml"), StandardCharsets.UTF_8);
        assertTrue(cfg.contains("entropy_threshold: 5.0"));
        assertTrue(cfg.contains("java"));
        assertTrue(cfg.contains("js"));
    }

    @Test
    void detectsFiveLanguagesAndStopsWalkEarly(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/a.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/b.py"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/c.go"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/d.rs"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/e.kt"), "x", StandardCharsets.UTF_8);
        assertEquals(0, InitCommand.run(root));
        String cfg = Files.readString(root.resolve(".aiv/config.yaml"), StandardCharsets.UTF_8);
        assertTrue(cfg.contains("java"));
        assertTrue(cfg.contains("python"));
    }

    @Test
    void skipsTargetSubtree(@TempDir Path root) throws Exception {
        Path target = Files.createDirectories(root.resolve("target"));
        Files.writeString(target.resolve("noise.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("keep.py"), "x", StandardCharsets.UTF_8);
        assertEquals(0, InitCommand.run(root));
        String cfg = Files.readString(root.resolve(".aiv/config.yaml"), StandardCharsets.UTF_8);
        assertTrue(cfg.contains("python"));
        assertTrue(cfg.contains("entropy_threshold: 5.0"));
    }

    @Test
    void skipsNodeModulesAndDotGit(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("node_modules/pkg/x.js"), "x", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(".git/objects"));
        Files.writeString(root.resolve(".git/objects/y.java"), "x", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ok.go"), "x", StandardCharsets.UTF_8);
        assertEquals(0, InitCommand.run(root));
        String cfg = Files.readString(root.resolve(".aiv/config.yaml"), StandardCharsets.UTF_8);
        assertTrue(cfg.contains("go"));
    }

    @Test
    void leavesExistingConfigUnchanged(@TempDir Path root) throws Exception {
        Path dotAiv = Files.createDirectories(root.resolve(".aiv"));
        Path cfg = dotAiv.resolve("config.yaml");
        Files.writeString(cfg, "gates: []\n", StandardCharsets.UTF_8);
        assertEquals(0, InitCommand.run(root));
        assertEquals("gates: []\n", Files.readString(cfg, StandardCharsets.UTF_8));
    }

    @Test
    void preservesExistingDesignRulesWhenWritingConfig(@TempDir Path root) throws Exception {
        Path dotAiv = Files.createDirectories(root.resolve(".aiv"));
        Path design = dotAiv.resolve("design-rules.yaml");
        Files.writeString(design, "custom: true\n", StandardCharsets.UTF_8);
        assertEquals(0, InitCommand.run(root));
        assertEquals("custom: true\n", Files.readString(design, StandardCharsets.UTF_8));
        assertTrue(Files.exists(dotAiv.resolve("config.yaml")));
    }
}
