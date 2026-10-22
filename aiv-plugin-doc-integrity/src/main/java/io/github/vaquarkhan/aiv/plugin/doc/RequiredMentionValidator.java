/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

import java.util.List;
import java.util.ArrayList;

/**
 * If doc mentions trigger keywords, it must also mention required items.
 *
 * @author Vaquar Khan
 */
public final class RequiredMentionValidator {

    private final DocRules rules;

    public RequiredMentionValidator(DocRules rules) {
        this.rules = rules;
    }

    public String validate(String docPath, String content) {
        List<String> all = validateAll(docPath, content);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<String> validateAll(String docPath, String content) {
        if (rules == null || content == null) return List.of();
        String contentLower = content.toLowerCase();
        String pathBase = docPath.contains("/") ? docPath.substring(docPath.lastIndexOf("/") + 1) : docPath;
        List<String> violations = new ArrayList<>();

        for (DocRules.DocConstraint c : rules.getDocConstraints()) {
            if (!scopeMatches(c.getScope(), pathBase)) continue;
            boolean triggerMatch = c.getTriggerKeywords().isEmpty()
                    || c.getTriggerKeywords().stream().anyMatch(k -> contentLower.contains(k.toLowerCase()));
            if (!triggerMatch) continue;
            for (String required : c.getRequiredMentions()) {
                if (!contentLower.contains(required.toLowerCase())) {
                    violations.add(String.format(
                            "Required mention '%s' missing in %s (constraint: %s)", required, docPath, c.getId()));
                }
            }
        }
        return violations;
    }

    private boolean scopeMatches(List<String> scope, String pathBase) {
        if (scope == null || scope.isEmpty()) return true;
        return scope.stream().anyMatch(s -> pathBase.equals(s) || pathBase.endsWith("/" + s));
    }
}
