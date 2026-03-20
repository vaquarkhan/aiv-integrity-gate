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

class RequiredMentionValidatorTest {

    @Test
    void returnsNullForNullRules() {
        var v = new RequiredMentionValidator(null);
        assertNull(v.validate("README.md", "content"));
    }

    @Test
    void returnsNullForNullContent() {
        var rules = new DocRules(List.of(), List.of());
        var v = new RequiredMentionValidator(rules);
        assertNull(v.validate("README.md", null));
    }

    @Test
    void returnsNullWhenScopeDoesNotMatch() {
        var c = new DocRules.DocConstraint("x", List.of("proto"), List.of("required.sh"), List.of("AGENTS.md"));
        var rules = new DocRules(List.of(c), List.of());
        var v = new RequiredMentionValidator(rules);
        assertNull(v.validate("README.md", "We use .proto files"));
    }

    @Test
    void returnsNullWhenTriggerDoesNotMatch() {
        var c = new DocRules.DocConstraint("x", List.of("proto"), List.of("required.sh"), List.of("README.md"));
        var rules = new DocRules(List.of(c), List.of());
        var v = new RequiredMentionValidator(rules);
        assertNull(v.validate("README.md", "We use JSON files"));
    }

    @Test
    void failsWhenRequiredMentionMissing() {
        var c = new DocRules.DocConstraint("x", List.of("proto"), List.of("required.sh"), List.of("README.md"));
        var rules = new DocRules(List.of(c), List.of());
        var v = new RequiredMentionValidator(rules);
        String result = v.validate("README.md", "We use .proto files for schema");
        assertNotNull(result);
    }

    @Test
    void passesWhenRequiredMentionPresent() {
        var c = new DocRules.DocConstraint("x", List.of("proto"), List.of("required.sh"), List.of("README.md"));
        var rules = new DocRules(List.of(c), List.of());
        var v = new RequiredMentionValidator(rules);
        assertNull(v.validate("README.md", "We use .proto files. Run required.sh to regenerate."));
    }
}
