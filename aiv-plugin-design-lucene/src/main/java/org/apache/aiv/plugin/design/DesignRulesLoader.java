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

package org.apache.aiv.plugin.design;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads design rules from YAML file.
 *
 * @author Vaquar Khan
 */
public final class DesignRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(DesignRulesLoader.class);

    public static DesignRules load(Path path) {
        if (!Files.exists(path)) {
            return new DesignRules(List.of());
        }
        try {
            String content = Files.readString(path);
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> root = yaml.load(content);
            if (root == null) {
                return new DesignRules(List.of());
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> constraintsList = (List<Map<String, Object>>) root.get("constraints");
            if (constraintsList == null) {
                return new DesignRules(List.of());
            }
            List<DesignRules.Constraint> constraints = new ArrayList<>();
            for (Map<String, Object> c : constraintsList) {
                String id = (String) c.get("id");
                @SuppressWarnings("unchecked")
                List<String> keywords = (List<String>) c.get("keywords");
                @SuppressWarnings("unchecked")
                List<String> forbidden = (List<String>) c.get("forbidden_calls");
                @SuppressWarnings("unchecked")
                List<String> required = (List<String>) c.get("required_calls");
                constraints.add(new DesignRules.Constraint(id, keywords, forbidden, required));
            }
            return new DesignRules(constraints);
        } catch (Exception e) {
            log.debug("Could not load design rules from {}", path, e);
            return new DesignRules(List.of());
        }
    }
}
