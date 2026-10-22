/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.doc;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Compares shell commands in docs against canonical command rules.
 *
 * @author Vaquar Khan
 */
public final class CommandCompletenessChecker {

    private final DocRules rules;

    public CommandCompletenessChecker(DocRules rules) {
        this.rules = rules;
    }

    public String validate(String docPath, String content) {
        List<String> all = validateAll(docPath, content);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<String> validateAll(String docPath, String content) {
        if (rules == null || content == null) return List.of();
        List<String> violations = new ArrayList<>();
        for (DocRules.CanonicalCommand cmd : rules.getCanonicalCommands()) {
            String pattern = cmd.getPattern();
            if (pattern == null || pattern.isEmpty()) continue;
            if (!Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(content).find()) {
                continue;
            }
            for (String flag : cmd.getRequiredFlags()) {
                if (!content.contains(flag)) {
                    violations.add(String.format(
                            "Command in %s matches '%s' but missing required flag '%s' (rule: %s)",
                            docPath, pattern, flag, cmd.getId()));
                }
            }
            for (String followup : cmd.getRequiredFollowup()) {
                if (!content.contains(followup)) {
                    violations.add(String.format(
                            "Command in %s matches '%s' but missing required followup '%s' (rule: %s)",
                            docPath, pattern, followup, cmd.getId()));
                }
            }
            Optional<String> missingReq = cmd.getRequiredCommands().stream()
                    .filter(reqCmd -> !content.contains(reqCmd))
                    .findFirst();
            if (missingReq.isPresent()) {
                violations.add(String.format(
                        "Command in %s matches '%s' but missing required command '%s' (rule: %s)",
                        docPath, pattern, missingReq.get(), cmd.getId()));
            }
        }
        return violations;
    }
}
