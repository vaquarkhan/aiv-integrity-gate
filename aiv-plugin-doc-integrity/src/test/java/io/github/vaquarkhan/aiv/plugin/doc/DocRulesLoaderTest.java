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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocRulesLoaderTest {

    @Test
    void returnsEmptyWhenFileMissing() {
        var rules = DocRulesLoader.load(Path.of("nonexistent/doc-rules.yaml"));
        assertNotNull(rules);
        assertTrue(rules.getDocConstraints().isEmpty());
        assertTrue(rules.getCanonicalCommands().isEmpty());
    }

    @Test
    void loadsDocConstraints(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("doc-rules.yaml"), """
                doc_constraints:
                  - id: proto-rule
                    trigger_keywords: [".proto"]
                    required_mentions: ["connect-gen-protos.sh"]
                    scope: [README.md]
                """);
        var rules = DocRulesLoader.load(dir.resolve("doc-rules.yaml"));
        assertEquals(1, rules.getDocConstraints().size());
        assertEquals("proto-rule", rules.getDocConstraints().get(0).getId());
        assertTrue(rules.getDocConstraints().get(0).getTriggerKeywords().contains(".proto"));
        assertTrue(rules.getDocConstraints().get(0).getRequiredMentions().contains("connect-gen-protos.sh"));
    }

    @Test
    void loadsCanonicalCommands(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("doc-rules.yaml"), """
                canonical_commands:
                  - id: sbt-build
                    pattern: "build/sbt"
                    required_flags: ["clean"]
                    required_followup: ["test:compile"]
                """);
        var rules = DocRulesLoader.load(dir.resolve("doc-rules.yaml"));
        assertEquals(1, rules.getCanonicalCommands().size());
        assertEquals("sbt-build", rules.getCanonicalCommands().get(0).getId());
        assertEquals("build/sbt", rules.getCanonicalCommands().get(0).getPattern());
        assertTrue(rules.getCanonicalCommands().get(0).getRequiredFlags().contains("clean"));
    }

    @Test
    void returnsEmptyForInvalidYaml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("doc-rules.yaml"), "invalid: yaml: [[[");
        var rules = DocRulesLoader.load(dir.resolve("doc-rules.yaml"));
        assertTrue(rules.getDocConstraints().isEmpty());
        assertTrue(rules.getCanonicalCommands().isEmpty());
    }

    @Test
    void returnsEmptyWhenYamlDocumentIsNull(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("doc-rules.yaml"), "");
        var rules = DocRulesLoader.load(dir.resolve("doc-rules.yaml"));
        assertTrue(rules.getDocConstraints().isEmpty());
        assertTrue(rules.getCanonicalCommands().isEmpty());
    }

    @Test
    void packagePrivateConstructorForCoverage() {
        assertNotNull(new DocRulesLoader());
    }
}
