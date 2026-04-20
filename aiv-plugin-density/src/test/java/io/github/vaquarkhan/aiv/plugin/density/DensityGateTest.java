/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aiv.plugin.density;

import io.github.vaquarkhan.aiv.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class DensityGateTest {

    @Test
    void getId() {
        assertEquals("density", new DensityGate().getId());
    }

    @Test
    void passesWhenNoJavaFiles() {
        var gate = new DensityGate();
        var ctx = context(List.of());
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void passesWhenLdrAndEntropyAboveThreshold() {
        String code = """
            public class Foo {
                public int bar(int x) {
                    if (x > 0) return x + 1;
                    return x - 1;
                }
            }
            """;
        var gate = new DensityGate();
        var ctx = context(List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, code)));
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void skipsNonMatchingExtensionAndEmptyContent() {
        var gate = new DensityGate();
        var ctx = context(List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "aaaa"),
                new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "")
        ));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsWhenEntropyTooLow() {
        String code = "a".repeat(500);
        var gate = new DensityGate();
        var ctx = context(List.of(new ChangedFile("x.java", ChangedFile.ChangeType.ADDED, code)));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("entropy"));
        assertFalse(r.getFindings().isEmpty());
        assertEquals("density.entropy", r.getFindings().get(0).getRuleId());
        assertEquals("x.java", r.getFindings().get(0).getFilePath());
        assertTrue(r.getFindings().get(0).getStartLine() >= 1);
    }

    @Test
    void failsWhenLdrTooLowForJava() {
        String code = """
            // random-ish comment to keep entropy from being the reason for failure
            // 0123456789 abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ
            public class Foo {
                public int bar() { return 1; }
            }
            """;
        var gate = new DensityGate();
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of(
                        "ldr_threshold", 0.90,
                        "entropy_threshold", 0.0
                ))),
                Map.of()
        );
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, code)),
                "");
        var ctx = new AIVContext(Paths.get("."), diff, config);
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("logic density"));
        assertTrue(r.getFindings().stream().anyMatch(f -> "density.ldr".equals(f.getRuleId())));
    }

    @Test
    void anchorLineOneWhenContentIsOnlyNewlines() {
        String code = "\n".repeat(500);
        var gate = new DensityGate();
        var ctx = context(List.of(new ChangedFile("only_nl.java", ChangedFile.ChangeType.ADDED, code)));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertEquals(1, r.getFindings().get(0).getStartLine());
    }

    @Test
    void calculateEntropyEmpty() {
        assertEquals(0, DensityGate.calculateEntropy(null));
        assertEquals(0, DensityGate.calculateEntropy(""));
    }

    @Test
    void calculateEntropyNonEmpty() {
        double e = DensityGate.calculateEntropy("abc");
        assertTrue(e > 0);
    }

    @Test
    void calculateLdrInvalidJava() {
        assertEquals(0, DensityGate.calculateLdr("not valid {"));
    }

    @Test
    void calculateLdrParsesRecordSyntax() {
        String code = """
                public class Outer {
                    private record R(int x) {}
                }
                """;
        assertTrue(DensityGate.calculateLdr(code) >= 0);
    }

    @Test
    void calculateLdrVisitsAllNodeTypes() {
        String code = """
            public class Foo {
                public int bar(int x) {
                    int y = x + 1;
                    if (y > 0) { y = y + 1; }
                    for (int i = 0; i < 2; i++) { y = y + i; }
                    while (y < 10) { y = y + 1; }
                    switch (y) { case 1: y = y + 1; break; default: y = y + 2; }
                    helper(y);
                    return y;
                }
                private void helper(int v) { System.out.println(v); }
            }
            """;
        double ldr = DensityGate.calculateLdr(code);
        assertTrue(ldr > 0);
    }

    @Test
    void effectiveEntropyThresholdRelaxesForSmallFiles() {
        assertTrue(DensityGate.effectiveEntropyThreshold("Foo.java", 300, 4.0) < 4.0);
        assertTrue(DensityGate.effectiveEntropyThreshold("foo.py", 1200, 4.0) < 4.0);
    }

    @Test
    void passesWhenRefactoringNetNegativeLoc() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.MODIFIED, "class Foo {}")),
                "", 10, 100, null, false, List.of(), Map.of("Foo.java", -90));
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("refactor_net_loc_threshold", -50))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenTrustedAuthor() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "a".repeat(500))),
                "", 1, 0, "committer@apache.org", true, false, List.of(), Map.of());
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", List.of("committer@apache.org")))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void trustedAuthorWithoutSignedCommitDoesNotBypassGate() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "a".repeat(500))),
                "", 1, 0, "committer@apache.org", false, false, List.of(), Map.of());
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", List.of("committer@apache.org")))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertFalse(gate.evaluate(ctx).isPassed());
    }

    @Test
    void signedButUntrustedAuthorDoesNotBypassGate() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "a".repeat(500))),
                "", 1, 0, "outsider@apache.org", true, false, List.of(), Map.of());
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", List.of("committer@apache.org")))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertFalse(gate.evaluate(ctx).isPassed());
    }

    @Test
    void nonListTrustedAuthorsDoesNotBypassGate() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "a".repeat(500))),
                "", 1, 0, "committer@apache.org", true, false, List.of(), Map.of());
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", "committer@apache.org"))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertFalse(gate.evaluate(ctx).isPassed());
    }

    @Test
    void ldrThresholdZeroPathStillEvaluatesAndCoversWarningBranch() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "class Foo { int x(){ return 1; } }")),
                "");
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of(
                        "ldr_threshold", 0.0,
                        "entropy_threshold", 0.0
                ))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void thresholdsDefaultWhenConfigTypesAreWrong() {
        String code = """
            public class Foo {
                public int bar(int x) {
                    if (x > 0) return x + 1;
                    return x - 1;
                }
            }
            """;
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, code)),
                "");
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of(
                        "ldr_threshold", "nope",
                        "entropy_threshold", "nope",
                        "refactor_net_loc_threshold", "nope"
                ))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    private AIVContext context(List<ChangedFile> files) {
        var diff = new Diff("main", "HEAD", files, "");
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("ldr_threshold", 0.25, "entropy_threshold", 5.0))),
                Map.of()
        );
        return new AIVContext(Paths.get("."), diff, config);
    }
}
