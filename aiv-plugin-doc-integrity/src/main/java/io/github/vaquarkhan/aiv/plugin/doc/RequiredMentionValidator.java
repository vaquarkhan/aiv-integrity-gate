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
        if (rules == null || content == null) return null;
        String contentLower = content.toLowerCase();
        String pathBase = docPath.contains("/") ? docPath.substring(docPath.lastIndexOf("/") + 1) : docPath;

        for (DocRules.DocConstraint c : rules.getDocConstraints()) {
            if (!scopeMatches(c.getScope(), pathBase)) continue;
            boolean triggerMatch = c.getTriggerKeywords().isEmpty()
                    || c.getTriggerKeywords().stream().anyMatch(k -> contentLower.contains(k.toLowerCase()));
            if (!triggerMatch) continue;
            for (String required : c.getRequiredMentions()) {
                if (!contentLower.contains(required.toLowerCase())) {
                    return String.format("Required mention '%s' missing in %s (constraint: %s)", required, docPath, c.getId());
                }
            }
        }
        return null;
    }

    private boolean scopeMatches(List<String> scope, String pathBase) {
        if (scope == null || scope.isEmpty()) return true;
        return scope.stream().anyMatch(s -> pathBase.equals(s) || pathBase.endsWith("/" + s));
    }
}
