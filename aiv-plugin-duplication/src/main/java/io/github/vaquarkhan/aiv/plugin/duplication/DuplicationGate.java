/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.duplication;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.util.FileExtensions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Advisory clone detector for the lines a change adds.
 *
 * <p>Motivation: GitClear's 2026 report measures within-commit copy/paste up 41% and duplicated
 * blocks up 81% since 2022 as AI assistance grew; duplication is the most measurable maintainability
 * tax of generated code. This gate asks one deterministic question: does a block of {@code min_lines}
 * (default 6) significant added lines already exist elsewhere in the repository?
 *
 * <p>Design: windows are built only over <em>added</em> lines (from the unified diff; whole file when
 * the file is new), hashed, and then every workspace source file is streamed once and probed against
 * that small set. No repository-wide index is kept in memory. Comments, blank lines, braces, and
 * import/package lines are ignored. Findings are always advisory (reviewer prompt, never a block).
 *
 * Config keys: {@code min_lines} (default 6), {@code max_files} (default 5000),
 * {@code max_file_bytes} (default 524288), {@code include_tests} (default false),
 * {@code file_extensions}/{@code languages} (shared convention).
 */
public final class DuplicationGate implements QualityGate {

    public static final String ID = "duplication";
    static final String RULE = "duplication.clone";

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", ".venv", "venv", "__pycache__", ".idea",
            "vendor", "generated", ".aiv");
    private static final Pattern TEST_PATH = Pattern.compile(
            "(^|/)(test|tests|__tests__|spec|specs)(/|$)|(Test|Tests|IT|Spec)\\.(java|kt|scala)$|_test\\.(py|go)$"
                    + "|^test_.*\\.py$|\\.(test|spec)\\.(js|ts|jsx|tsx)$");

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Map<String, Object> cfg = context.getConfig().findGate(ID).map(g -> g.getConfig()).orElse(Map.of());
        int window = intVal(cfg.get("min_lines"), 6);
        int maxFiles = intVal(cfg.get("max_files"), 5000);
        long maxBytes = intVal(cfg.get("max_file_bytes"), 524288);
        boolean includeTests = Boolean.TRUE.equals(cfg.get("include_tests"));
        Set<String> exts = FileExtensions.fromConfig(cfg);

        Set<String> addedTexts = addedLineTexts(context.getDiff().getRawDiff());
        boolean hasDiffIndex = !addedTexts.isEmpty();

        // window key -> (changed path, first original line)
        Map<String, String[]> probes = new HashMap<>();
        Set<String> changedPaths = new HashSet<>();
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            String path = norm(f.getPath());
            if (!FileExtensions.matches(path, exts)) {
                continue;
            }
            if (!includeTests && isTestPath(path)) {
                continue;
            }
            changedPaths.add(path);
            boolean fileIsAdded = f.getChangeType() == ChangedFile.ChangeType.ADDED;
            List<LineNormalizer.Line> lines = new ArrayList<>();
            for (LineNormalizer.Line l : LineNormalizer.normalize(f.getContent())) {
                if (!hasDiffIndex ? fileIsAdded : (fileIsAdded || addedTexts.contains(l.text()))) {
                    lines.add(l);
                }
            }
            for (int i = 0; i + window <= lines.size(); i++) {
                probes.putIfAbsent(LineNormalizer.windowKey(lines, i, window),
                        new String[]{path, String.valueOf(lines.get(i).lineNumber())});
            }
        }
        if (probes.isEmpty()) {
            return GateResult.pass(ID);
        }

        // changedPath -> (matchedPath -> first [changedLine, matchedLine])
        Map<String, Map<String, int[]>> hits = new LinkedHashMap<>();
        scanWorkspace(context.getWorkspace(), exts, maxFiles, maxBytes, window, probes, changedPaths, hits);

        if (hits.isEmpty()) {
            return GateResult.pass(ID);
        }
        List<Finding> findings = new ArrayList<>();
        List<String> msgs = new ArrayList<>();
        for (Map.Entry<String, Map<String, int[]>> e : hits.entrySet()) {
            for (Map.Entry<String, int[]> m : e.getValue().entrySet()) {
                String msg = "Added block (" + window + "+ significant lines) duplicates existing code in "
                        + m.getKey() + ":" + m.getValue()[1] + " (consider reusing or extracting)";
                findings.add(Finding.atLine(RULE, e.getKey(), m.getValue()[0], msg));
                msgs.add(e.getKey() + ":" + m.getValue()[0] + " " + msg);
            }
        }
        return GateResult.advisory(ID, String.join("\n", msgs), findings);
    }

    private static void scanWorkspace(Path workspace, Set<String> exts, int maxFiles, long maxBytes, int window,
                                      Map<String, String[]> probes, Set<String> changedPaths,
                                      Map<String, Map<String, int[]>> hits) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return;
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
                    String rel = norm(workspace.relativize(file).toString());
                    if (!FileExtensions.matches(rel, exts) || changedPaths.contains(rel) || attrs.size() > maxBytes) {
                        return FileVisitResult.CONTINUE;
                    }
                    String content = READER.read(file);
                    if (content == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    List<LineNormalizer.Line> lines = LineNormalizer.normalize(content);
                    for (int i = 0; i + window <= lines.size(); i++) {
                        String[] probe = probes.get(LineNormalizer.windowKey(lines, i, window));
                        if (probe != null) {
                            hits.computeIfAbsent(probe[0], k -> new LinkedHashMap<>())
                                    .putIfAbsent(rel, new int[]{Integer.parseInt(probe[1]), lines.get(i).lineNumber()});
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Precision-first: unreadable workspace means no advisory, never a failure.
        }
    }

    static Set<String> addedLineTexts(String rawDiff) {
        Set<String> out = new HashSet<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return out;
        }
        boolean inFile = false;
        for (String line : rawDiff.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                inFile = !line.substring(4).trim().equals("/dev/null");
                continue;
            }
            if (inFile && line.startsWith("+") && !line.startsWith("+++")) {
                String t = line.substring(1).trim().replaceAll("\\s+", " ");
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    static boolean isTestPath(String path) {
        return TEST_PATH.matcher(path.toLowerCase(Locale.ROOT)).find()
                || TEST_PATH.matcher(path).find();
    }

    static String norm(String p) {
        return p == null ? "" : p.replace('\\', '/');
    }

    static int intVal(Object v, int dflt) {
        return v instanceof Number n ? n.intValue() : dflt;
    }

    /** Seams for tests (IO failure paths). */
    @FunctionalInterface
    interface Walker {
        void walk(Path start, java.nio.file.FileVisitor<? super Path> visitor) throws IOException;
    }

    @FunctionalInterface
    interface Reader {
        String read(Path file);
    }

    static Walker WALKER = Files::walkFileTree;

    static Reader READER = file -> {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null; // binary / non-UTF-8 / unreadable: not a clone candidate
        }
    };
}
