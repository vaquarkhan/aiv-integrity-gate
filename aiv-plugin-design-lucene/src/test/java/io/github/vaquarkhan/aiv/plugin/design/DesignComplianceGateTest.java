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

package io.github.vaquarkhan.aiv.plugin.design;

import io.github.vaquarkhan.aiv.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class DesignComplianceGateTest {

    @Test
    void getId() {
        assertEquals("design", new DesignComplianceGate().getId());
    }

    @Test
    void lineOfCaseInsensitiveSubstringEdgeCases() throws Exception {
        Method m = DesignComplianceGate.class.getDeclaredMethod("lineOfCaseInsensitiveSubstring", String.class, String.class);
        m.setAccessible(true);
        assertEquals(1, (int) m.invoke(null, "hello world", ""));
        assertEquals(1, (int) m.invoke(null, "hello world", null));
        assertEquals(1, (int) m.invoke(null, "hello world", "nomatch"));
        assertEquals(2, (int) m.invoke(null, "a\nb", "b"));
    }

    @Test
    void containsPatternCoversBlankAndFallbackBranches() throws Exception {
        Method m = DesignComplianceGate.class.getDeclaredMethod("containsPattern", String.class, String.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(null, null, "abc"));
        assertFalse((boolean) m.invoke(null, "abc", "   "));
        assertTrue((boolean) m.invoke(null, "alpha beta", "pha b"));
    }

    @Test
    void passesWhenNoRules(@TempDir Path dir) throws Exception {
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "anything")), ".aiv/nonexistent.yaml");
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void usesDefaultRulesPathWhenGateConfigOmitsKey(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: forbid-exit
                keywords: []
                forbidden_calls: [System.exit]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var diff = new Diff("main", "HEAD", List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "class A { void x(){ System.exit(1);} }")), "");
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("design", true, Map.of())),
                Map.of()
        );
        var ctx = new AIVContext(dir.toAbsolutePath(), diff, config);
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
    }

    @Test
    void failsWhenForbiddenCallPresent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: test
                keywords: []
                forbidden_calls: [table.removeSnapshots]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "x.table.removeSnapshots()")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("Forbidden"));
        assertFalse(r.getFindings().isEmpty());
        assertTrue(r.getFindings().get(0).getRuleId().startsWith("design.forbidden."));
    }

    @Test
    void passesWhenConstraintDoesNotApply(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: test
                keywords: [ExpireSnapshots]
                forbidden_calls: [removeSnapshots]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "public void bar() {}")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void validatesPythonWhenInExtensions(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: no-eval
                keywords: []
                forbidden_calls: [eval(]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var ctx = contextWithExtensions(dir, List.of(new ChangedFile("main.py", ChangedFile.ChangeType.ADDED, "x = eval('1+1')")), ".aiv/design-rules.yaml", List.of(".java", ".py"));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
    }

    @Test
    void failsWhenRequiredCallMissing(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: snapshot-expiration
                keywords: []
                forbidden_calls: []
                required_calls: [ExpireSnapshots]
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "public class Foo {}")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("Required"));
        assertTrue(r.getFindings().stream().anyMatch(f -> f.getRuleId().startsWith("design.required.")));
    }

    @Test
    void appliesConstraintWhenKeywordMatchesPath(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: forbid-exit
                keywords: [Main]
                forbidden_calls: [System.exit]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED, "class Main { void x(){ System.exit(1); } }")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("Forbidden"));
    }

    @Test
    void ignoresNonMatchingExtensions(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: forbid-exit
                keywords: []
                forbidden_calls: [System.exit]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "System.exit(1)")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void passesWhenForbiddenListDoesNotMatch(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: forbid-foo
                keywords: []
                forbidden_calls: [foo()]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED, "class Main { void x(){} }")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void forbiddenCallUsesTokenAwareMatching(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: forbid-remove
                keywords: []
                forbidden_calls: [remove]
                required_calls: []
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED,
                "class Main { void x(){ table.removeSnapshots(); } }")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void passesWhenRequiredCallPresent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: require-foo
                keywords: []
                forbidden_calls: []
                required_calls: [Foo.bar]
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED, "class Main { void x(){ Foo.bar(); } }")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void requiredCallUsesTokenAwareMatching(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/design-rules.yaml"), """
            constraints:
              - id: require-expire
                keywords: []
                forbidden_calls: []
                required_calls: [ExpireSnapshots]
            """);
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED,
                "class Main { void x(){ ExpireSnapshotsV2.run(); } }")), ".aiv/design-rules.yaml");
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
    }

    private AIVContext context(Path workspace, List<ChangedFile> files, String rulesPath) {
        var diff = new Diff("main", "HEAD", files, "");
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("design", true, Map.of("rules_path", rulesPath))),
                Map.of()
        );
        return new AIVContext(workspace.toAbsolutePath(), diff, config);
    }

    private AIVContext contextWithExtensions(Path workspace, List<ChangedFile> files, String rulesPath, List<String> extensions) {
        var diff = new Diff("main", "HEAD", files, "");
        var gateConfig = Map.<String, Object>of("rules_path", rulesPath, "file_extensions", extensions);
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("design", true, gateConfig)),
                Map.of()
        );
        return new AIVContext(workspace.toAbsolutePath(), diff, config);
    }
}
