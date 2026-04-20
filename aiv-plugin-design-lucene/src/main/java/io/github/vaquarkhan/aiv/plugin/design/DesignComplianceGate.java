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

package io.github.vaquarkhan.aiv.plugin.design;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.util.FileExtensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Design compliance gate using YAML rules. Checks forbidden/required patterns.
 * For {@code .java} sources, comments and string literals are stripped before token-aware checks.
 *
 * @author Vaquar Khan
 */
public final class DesignComplianceGate implements QualityGate {

    private static final String RULES_PATH_KEY = "rules_path";

    @Override
    public String getId() {
        return "design";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        String rulesPath = getConfigString(context, RULES_PATH_KEY, ".aiv/design-rules.yaml");
        Set<String> extensions = FileExtensions.fromConfig(getGateConfig(context));
        DesignRules rules = DesignRulesLoader.load(context.getWorkspace().resolve(rulesPath));

        if (rules == null || rules.getConstraints().isEmpty()) {
            return GateResult.pass(getId());
        }

        List<String> violations = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            if (!FileExtensions.matches(file.getPath(), extensions)) {
                continue;
            }
            String content = file.getContent();
            String path = file.getPath();
            String pathLower = path.toLowerCase();
            String ruleSurface = JavaDesignSurface.lowerForRuleMatching(path, content);
            for (DesignRules.Constraint c : rules.getConstraints()) {
                boolean applies = c.getKeywords().isEmpty()
                        || c.getKeywords().stream().anyMatch(k ->
                        containsPattern(ruleSurface, k) || containsPattern(pathLower, k));
                if (!applies) {
                    continue;
                }
                for (String forbidden : c.getForbiddenCalls()) {
                    if (containsPattern(ruleSurface, forbidden)) {
                        String msg = String.format("Forbidden call '%s' in %s (constraint: %s)", forbidden, path, c.getId());
                        violations.add(msg);
                        int line = lineOfCaseInsensitiveSubstring(content, forbidden);
                        findings.add(Finding.atLine("design.forbidden." + c.getId(), path, line, msg));
                    }
                }
                for (String required : c.getRequiredCalls()) {
                    if (!containsPattern(ruleSurface, required)) {
                        String msg = String.format("Required call '%s' missing in %s (constraint: %s)", required, path, c.getId());
                        violations.add(msg);
                        findings.add(Finding.atLine("design.required." + c.getId(), path, 1, msg));
                    }
                }
            }
        }
        if (violations.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("\n", violations), findings);
    }

    private static int lineOfCaseInsensitiveSubstring(String content, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 1;
        }
        String lower = content.toLowerCase();
        int idx = lower.indexOf(needle.toLowerCase());
        if (idx < 0) {
            return 1;
        }
        return content.substring(0, idx).split("\n", -1).length;
    }

    private static boolean containsPattern(String surface, String pattern) {
        if (surface == null || pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalizedPattern = pattern.toLowerCase();
        if (!isTokenPattern(normalizedPattern)) {
            return phrasePattern(normalizedPattern).matcher(surface).find();
        }
        return tokenPattern(normalizedPattern).matcher(surface).find();
    }

    private static boolean isTokenPattern(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '$'
                    || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static Pattern tokenPattern(String pattern) {
        String[] parts = pattern.split("\\.");
        StringBuilder regex = new StringBuilder("(?<![a-z0-9_$])");
        for (int i = 0; i < parts.length; i++) {
            regex.append(Pattern.quote(parts[i]));
            if (i + 1 < parts.length) {
                regex.append("\\s*\\.\\s*");
            }
        }
        regex.append("(?![a-z0-9_$])");
        return Pattern.compile(regex.toString());
    }

    private static Pattern phrasePattern(String pattern) {
        String normalized = pattern.trim().replaceAll("\\s+", " ");
        StringBuilder regex = new StringBuilder("(?<![a-z0-9_$])");
        String[] parts = normalized.split(" ");
        for (int i = 0; i < parts.length; i++) {
            regex.append(Pattern.quote(parts[i]));
            if (i + 1 < parts.length) {
                regex.append("\\s+");
            }
        }
        regex.append("(?!\\s+[a-z0-9_$])(?![a-z0-9_$])");
        return Pattern.compile(regex.toString());
    }

    private Map<String, Object> getGateConfig(AIVContext context) {
        return context.getConfig().getGates().stream()
                .filter(g -> getId().equals(g.getId()))
                .findFirst()
                .map(AIVConfig.GateConfig::getConfig)
                .orElse(Map.of());
    }

    private String getConfigString(AIVContext context, String key, String defaultValue) {
        Object v = getGateConfig(context).get(key);
        return v != null ? v.toString() : defaultValue;
    }
}
