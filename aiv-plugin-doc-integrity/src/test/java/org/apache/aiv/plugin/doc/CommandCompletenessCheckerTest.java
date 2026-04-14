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

package org.apache.aiv.plugin.doc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCompletenessCheckerTest {

    @Test
    void returnsNullForNullRules() {
        var c = new CommandCompletenessChecker(null);
        assertNull(c.validate("README.md", "content"));
    }

    @Test
    void returnsNullForNullContent() {
        var rules = new DocRules(List.of(), List.of());
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", null));
    }

    @Test
    void returnsNullWhenPatternDoesNotMatch() {
        var cmd = new DocRules.CanonicalCommand("x", "build/sbt.*package", List.of("clean"), List.of(), List.of());
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", "Run mvn package"));
    }

    @Test
    void failsWhenRequiredFlagMissing() {
        var cmd = new DocRules.CanonicalCommand("x", "build/sbt.*package", List.of("clean"), List.of(), List.of());
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        String result = c.validate("README.md", "Run build/sbt -Phive package");
        assertNotNull(result);
    }

    @Test
    void passesWhenAllRequiredPresent() {
        var cmd = new DocRules.CanonicalCommand("x", "build/sbt.*package", List.of("clean"), List.of("test:compile"), List.of());
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", "Run build/sbt clean -Phive package and build/sbt test:compile"));
    }

    @Test
    void failsWhenRequiredFollowupMissing() {
        var cmd = new DocRules.CanonicalCommand("x", "mvn verify", List.of(),
                List.of("install"), List.of());
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        String r = c.validate("README.md", "Run mvn verify on CI");
        assertNotNull(r);
        assertTrue(r.contains("followup"));
    }

    @Test
    void failsWhenRequiredCommandMissing() {
        var cmd = new DocRules.CanonicalCommand("x", "git push", List.of(), List.of(),
                List.of("git status"));
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        String r = c.validate("README.md", "Then git push origin");
        assertNotNull(r);
        assertTrue(r.contains("required command"));
    }

    @Test
    void passesWhenAllRequiredCommandsPresent() {
        var cmd = new DocRules.CanonicalCommand("x", "mvn package", List.of(), List.of(),
                List.of("clean", "install"));
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", "mvn clean install package"));
    }

    @Test
    void runsMultipleCanonicalCommandRulesInSequence() {
        var a = new DocRules.CanonicalCommand("a", "docker", List.of(), List.of(), List.of());
        var b = new DocRules.CanonicalCommand("b", "compose", List.of(), List.of(), List.of());
        var rules = new DocRules(List.of(), List.of(a, b));
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", "Run docker compose up"));
    }

    @Test
    void passesWhenAllRequiredFollowupsPresent() {
        var cmd = new DocRules.CanonicalCommand("x", "npm run", List.of(), List.of("build", "test"), List.of());
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", "npm run build and test locally"));
    }

    @Test
    void skipsRuleWhenPatternNullOrEmpty() {
        var skipNull = new DocRules.CanonicalCommand("a", null, List.of(), List.of(), List.of());
        var skipEmpty = new DocRules.CanonicalCommand("b", "", List.of(), List.of(), List.of());
        var run = new DocRules.CanonicalCommand("c", "echo", List.of(), List.of(), List.of());
        var rules = new DocRules(List.of(), List.of(skipNull, skipEmpty, run));
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", "echo hello"));
    }

    @Test
    void passesWhenRequiredCommandsEmpty() {
        var cmd = new DocRules.CanonicalCommand("x", "gradle", List.of(), List.of(), List.of());
        var rules = new DocRules(List.of(), List.of(cmd));
        var c = new CommandCompletenessChecker(rules);
        assertNull(c.validate("README.md", "use gradle wrapper"));
    }
}
