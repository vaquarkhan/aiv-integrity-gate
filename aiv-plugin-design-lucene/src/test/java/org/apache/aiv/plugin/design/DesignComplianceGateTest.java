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

package org.apache.aiv.plugin.design;

import org.apache.aiv.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void passesWhenNoRules(@TempDir Path dir) throws Exception {
        var gate = new DesignComplianceGate();
        var ctx = context(dir, List.of(new ChangedFile("a.java", ChangedFile.ChangeType.ADDED, "anything")), ".aiv/nonexistent.yaml");
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
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
