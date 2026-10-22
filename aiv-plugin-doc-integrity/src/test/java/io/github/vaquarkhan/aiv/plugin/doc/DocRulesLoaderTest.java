/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
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
