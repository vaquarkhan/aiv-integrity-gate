/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

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
