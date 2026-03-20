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

import org.apache.aiv.model.AIVConfig;
import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.QualityGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Documentation integrity gate. Validates paths, cross-references, required mentions,
 * command completeness, and path fabrication in markdown and text files.
 *
 * @author Vaquar Khan
 */
public final class DocIntegrityGate implements QualityGate {

    private static final String RULES_PATH_KEY = "rules_path";
    private static final String DEFAULT_RULES_PATH = ".aiv/doc-rules.yaml";

    @Override
    public String getId() {
        return "doc-integrity";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        List<ChangedFile> docFiles = filterDocFiles(context.getDiff().getChangedFiles());
        if (docFiles.isEmpty()) {
            return GateResult.pass(getId());
        }

        String rulesPath = getConfigString(context, RULES_PATH_KEY, DEFAULT_RULES_PATH);
        DocRules rules = DocRulesLoader.load(context.getWorkspace().resolve(rulesPath));

        List<String> violations = new ArrayList<>();

        FileExistenceValidator fileExistence = new FileExistenceValidator(context.getWorkspace());
        CrossReferenceChecker crossRef = new CrossReferenceChecker(context.getWorkspace());
        RequiredMentionValidator requiredMention = new RequiredMentionValidator(rules);
        CommandCompletenessChecker commandChecker = new CommandCompletenessChecker(rules);
        PathFabricationDetector pathFabrication = new PathFabricationDetector(context);

        for (ChangedFile file : docFiles) {
            String path = file.getPath();
            String content = file.getContent();

            String v = fileExistence.validate(path, content);
            if (v != null) violations.add(v);

            v = crossRef.validate(path, content);
            if (v != null) violations.add(v);

            v = requiredMention.validate(path, content);
            if (v != null) violations.add(v);

            v = commandChecker.validate(path, content);
            if (v != null) violations.add(v);

            v = pathFabrication.validate(path, content);
            if (v != null) violations.add(v);
        }

        if (violations.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("; ", violations));
    }

    private List<ChangedFile> filterDocFiles(List<ChangedFile> files) {
        return files.stream()
                .filter(f -> DocFileExtensions.matches(f.getPath()))
                .toList();
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
