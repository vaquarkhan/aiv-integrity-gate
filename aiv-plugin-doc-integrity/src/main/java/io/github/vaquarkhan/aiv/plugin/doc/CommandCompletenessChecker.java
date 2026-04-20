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
