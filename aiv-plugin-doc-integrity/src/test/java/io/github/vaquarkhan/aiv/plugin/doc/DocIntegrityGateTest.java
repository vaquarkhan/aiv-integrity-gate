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

package io.github.vaquarkhan.aiv.plugin.doc;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocIntegrityGateTest {

    @Test
    void passesWhenNoDocFiles() {
        var gate = new DocIntegrityGate();
        var ctx = context(Path.of("."), List.of(
                new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "public class Foo {}")
        ));
        GateResult r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void passesWhenDocFileHasValidPath(@TempDir Path dir) throws Exception {
        Path readme = dir.resolve("docs/README.md");
        Files.createDirectories(readme.getParent());
        Files.writeString(readme, "See the docs");
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("docs/README.md", ChangedFile.ChangeType.ADDED, "See docs at docs/")
        ));
        GateResult r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void failsWhenRequiredMentionMissing(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/doc-rules.yaml"), """
                doc_constraints:
                  - id: proto-rule
                    trigger_keywords: [".proto"]
                    required_mentions: ["connect-gen-protos.sh"]
                    scope: [README.md]
                """);
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "We use .proto files for schema.")
        ));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("connect-gen-protos.sh"));
    }

    @Test
    void gateIdIsDocIntegrity() {
        var gate = new DocIntegrityGate();
        assertEquals("doc-integrity", gate.getId());
    }

    @Test
    void passesWhenDocFileTxt() {
        var gate = new DocIntegrityGate();
        var ctx = context(Path.of("."), List.of(
                new ChangedFile("notes.txt", ChangedFile.ChangeType.ADDED, "Simple text")
        ));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenDocFileRst() {
        var gate = new DocIntegrityGate();
        var ctx = context(Path.of("."), List.of(
                new ChangedFile("index.rst", ChangedFile.ChangeType.ADDED, "Restructured text")
        ));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenRequiredMentionPresent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".aiv"));
        Files.writeString(dir.resolve(".aiv/doc-rules.yaml"), """
                doc_constraints:
                  - id: golden-rule
                    trigger_keywords: ["golden file"]
                    required_mentions: ["SPARK_GENERATE_GOLDEN_FILES"]
                    scope: [README.md]
                """);
        var gate = new DocIntegrityGate();
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "For golden file tests set SPARK_GENERATE_GOLDEN_FILES=1.")
        ));
        GateResult r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    private AIVContext context(Path workspace, List<ChangedFile> files) {
        var diff = new Diff("main", "HEAD", files, "", 0, 0, null, false);
        var gates = List.of(
                new AIVConfig.GateConfig("doc-integrity", true, Map.of("rules_path", ".aiv/doc-rules.yaml"))
        );
        var config = new AIVConfig(gates, Map.of());
        return new AIVContext(workspace, diff, config);
    }
}
