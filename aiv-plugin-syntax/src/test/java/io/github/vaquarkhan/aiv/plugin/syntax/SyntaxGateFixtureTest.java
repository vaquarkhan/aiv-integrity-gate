/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.vaquarkhan.aiv.plugin.syntax;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-driven regression harness. Every file under {@code src/test/resources/fixtures/should-pass}
 * must be accepted by the SyntaxGate; every file under {@code should-block} must be flagged.
 * Adding a case is a file drop — no new Java. The fixture filename is used as the changed-file
 * path, so extension dispatch and the Dockerfile.java / tsconfig carve-outs run exactly as in
 * production. Python is stubbed OK so the suite is deterministic/offline.
 */
class SyntaxGateFixtureTest {

    private static final Path FIXTURES = Paths.get("src", "test", "resources", "fixtures");

    private static AIVContext ctx(String path, String content) {
        Diff diff = new Diff(
                "base",
                "head",
                List.of(new ChangedFile(path, ChangedFile.ChangeType.MODIFIED, content)),
                "");
        return new AIVContext(Paths.get("."), diff, new AIVConfig(List.of(), Map.of()));
    }

    private static SyntaxGate gate() {
        return new SyntaxGate(src -> ValidationResult.ok());
    }

    private static List<Path> filesIn(String subdir) {
        Path dir = FIXTURES.resolve(subdir);
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> out = new ArrayList<>();
            s.filter(Files::isRegularFile).forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read fixtures in " + dir, e);
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestFactory
    Stream<DynamicTest> shouldPassFixturesAreAccepted() {
        return filesIn("should-pass").stream().map(f -> DynamicTest.dynamicTest(
                "should-pass/" + f.getFileName(),
                () -> {
                    GateResult r = gate().evaluate(ctx(f.getFileName().toString(), read(f)));
                    assertTrue(
                            r.isPassed(),
                            "Expected PASS for " + f.getFileName() + " but gate blocked: " + r.getMessage());
                }));
    }

    @TestFactory
    Stream<DynamicTest> shouldBlockFixturesAreFlagged() {
        return filesIn("should-block").stream().map(f -> DynamicTest.dynamicTest(
                "should-block/" + f.getFileName(),
                () -> {
                    GateResult r = gate().evaluate(ctx(f.getFileName().toString(), read(f)));
                    assertFalse(
                            r.isPassed(),
                            "Expected BLOCK for " + f.getFileName() + " but gate passed");
                    assertTrue(
                            r.getFindings().stream().anyMatch(x -> "syntax.parse".equals(x.getRuleId())),
                            "Expected a syntax.parse finding for " + f.getFileName());
                }));
    }
}
