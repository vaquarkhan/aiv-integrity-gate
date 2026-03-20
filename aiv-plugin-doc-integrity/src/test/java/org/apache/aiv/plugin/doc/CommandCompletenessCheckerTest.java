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
}
