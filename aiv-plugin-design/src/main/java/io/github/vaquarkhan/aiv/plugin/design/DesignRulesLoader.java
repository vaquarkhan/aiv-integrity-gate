/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.design;

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
            throw new IllegalArgumentException("Could not load design rules from " + path + ": " + e.getMessage(), e);
        }
    }
}
