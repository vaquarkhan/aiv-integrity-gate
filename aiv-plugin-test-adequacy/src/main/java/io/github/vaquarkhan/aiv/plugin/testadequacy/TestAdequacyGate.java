/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.testadequacy;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Test-adequacy gate.
 *
 * <ul>
 *   <li><b>{@code test-adequacy.assertion-free-test}</b> (hard): a test function introduced by the
 *   change has no assertion, expectation, verification or exception check. Deterministic, added-lines
 *   only. A test that cannot fail is not evidence.</li>
 *   <li><b>{@code test-adequacy.missing-tests}</b> (advisory): a production source file was added or
 *   modified, no test file is part of the change, and no conventionally named test for it exists in the
 *   workspace. This is a prompt for the reviewer, never a block on its own.</li>
 * </ul>
 *
 * Config keys: {@code check_assertions} (default true), {@code require_tests} (default true),
 * {@code max_workspace_files} (default 20000).
 */
public final class TestAdequacyGate implements QualityGate {

    public static final String ID = "test-adequacy";
    static final String MISSING_RULE = "test-adequacy.missing-tests";

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", ".venv", "venv", "__pycache__", ".idea", "vendor");

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Map<String, Object> cfg = context.getConfig().findGate(ID)
                .map(g -> g.getConfig()).orElse(Map.of());
        boolean checkAssertions = bool(cfg.get("check_assertions"), true);
        boolean requireTests = bool(cfg.get("require_tests"), true);
        int maxFiles = intVal(cfg.get("max_workspace_files"), 20000);

        AddedLines added = AddedLines.parse(context.getDiff().getRawDiff());
        List<ChangedFile> files = context.getDiff().getChangedFiles();

        Set<String> changedTestStems = new HashSet<>();
        for (ChangedFile f : files) {
            if (TestPaths.isCodeFile(f.getPath()) && TestPaths.isTestPath(f.getPath())) {
                changedTestStems.add(TestPaths.stem(TestPaths.basename(f.getPath())).toLowerCase(Locale.ROOT));
            }
        }

        List<Finding> hard = new ArrayList<>();
        List<Finding> soft = new ArrayList<>();
        Set<String> workspaceStems = null;

        for (ChangedFile f : files) {
            String path = f.getPath();
            if (!TestPaths.isCodeFile(path)) {
                continue;
            }
            boolean isAdded = f.getChangeType() == ChangedFile.ChangeType.ADDED;
            if (TestPaths.isTestPath(path)) {
                if (checkAssertions) {
                    hard.addAll(AssertionScanner.scan(path, f.getContent(), isAdded, added));
                }
                continue;
            }
            if (!requireTests || !TestPaths.isProductionCandidate(path)) {
                continue;
            }
            Set<String> candidates = TestPaths.candidateTestStems(path);
            if (intersects(candidates, changedTestStems)) {
                continue;
            }
            if (workspaceStems == null) {
                workspaceStems = indexWorkspaceTestStems(context.getWorkspace(), maxFiles);
            }
            if (intersects(candidates, workspaceStems)) {
                continue;
            }
            soft.add(Finding.atLine(MISSING_RULE, path, 1,
                    "Production file changed with no test in this change and no conventional test found "
                            + "(expected one of: " + String.join(", ", candidates) + ")"));
        }

        if (!hard.isEmpty()) {
            List<Finding> all = new ArrayList<>(hard);
            all.addAll(soft);
            return GateResult.fail(ID, joinMessages(all), all);
        }
        if (!soft.isEmpty()) {
            return GateResult.advisory(ID, joinMessages(soft), soft);
        }
        return GateResult.pass(ID);
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> indexWorkspaceTestStems(Path workspace, int maxFiles) {
        Set<String> stems = new HashSet<>();
        if (workspace == null || !Files.isDirectory(workspace)) {
            return stems;
        }
        int[] seen = {0};
        try {
            WALKER.walk(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name != null && SKIP_DIRS.contains(name.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++seen[0] > maxFiles) {
                        return FileVisitResult.TERMINATE;
                    }
                    String rel = workspace.relativize(file).toString();
                    if (TestPaths.isCodeFile(rel) && TestPaths.isTestPath(rel)) {
                        stems.add(TestPaths.stem(TestPaths.basename(rel)).toLowerCase(Locale.ROOT));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Precision-first: an unreadable workspace yields no advisory, never a failure.
            return stems;
        }
        return stems;
    }

    /** Seam for tests (IO failure path). */
    @FunctionalInterface
    interface Walker {
        void walk(Path start, java.nio.file.FileVisitor<? super Path> visitor) throws IOException;
    }

    static Walker WALKER = Files::walkFileTree;

    private static String joinMessages(List<Finding> findings) {
        List<String> msgs = new ArrayList<>();
        for (Finding f : findings) {
            msgs.add(f.getFilePath() + ":" + f.getStartLine() + " " + f.getMessage());
        }
        return String.join("\n", msgs);
    }

    static boolean bool(Object v, boolean dflt) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return dflt;
    }

    static int intVal(Object v, int dflt) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return dflt;
    }
}
