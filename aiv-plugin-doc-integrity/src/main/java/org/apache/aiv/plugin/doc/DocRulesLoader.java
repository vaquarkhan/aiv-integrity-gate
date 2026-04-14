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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class DocRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(DocRulesLoader.class);

    DocRulesLoader() {
    }

    public static DocRules load(Path path) {
        if (!Files.exists(path)) {
            return new DocRules(List.of(), List.of());
        }
        try {
            String content = Files.readString(path);
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> root = yaml.load(content);
            if (root == null) return new DocRules(List.of(), List.of());
            List<DocRules.DocConstraint> constraints = parseDocConstraints(root);
            List<DocRules.CanonicalCommand> commands = parseCanonicalCommands(root);
            return new DocRules(constraints, commands);
        } catch (Exception e) {
            log.debug("Could not load doc rules from {}", path, e);
            return new DocRules(List.of(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<DocRules.DocConstraint> parseDocConstraints(Map<String, Object> root) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("doc_constraints");
        if (list == null) return Collections.emptyList();
        List<DocRules.DocConstraint> result = new ArrayList<>();
        for (Map<String, Object> c : list) {
            String id = (String) c.get("id");
            List<String> trigger = (List<String>) c.get("trigger_keywords");
            List<String> required = (List<String>) c.get("required_mentions");
            List<String> scope = (List<String>) c.get("scope");
            result.add(new DocRules.DocConstraint(id, trigger, required, scope));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<DocRules.CanonicalCommand> parseCanonicalCommands(Map<String, Object> root) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("canonical_commands");
        if (list == null) return Collections.emptyList();
        List<DocRules.CanonicalCommand> result = new ArrayList<>();
        for (Map<String, Object> c : list) {
            String id = (String) c.get("id");
            String pattern = (String) c.get("pattern");
            List<String> flags = (List<String>) c.get("required_flags");
            List<String> followup = (List<String>) c.get("required_followup");
            List<String> commands = (List<String>) c.get("required_commands");
            result.add(new DocRules.CanonicalCommand(id, pattern, flags, followup, commands));
        }
        return result;
    }
}
