/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.design;

import java.util.Collections;
import java.util.List;

/**
 * Parsed design rules from YAML.
 *
 * @author Vaquar Khan
 */
public final class DesignRules {

    private final List<Constraint> constraints;

    public DesignRules(List<Constraint> constraints) {
        this.constraints = constraints == null ? Collections.emptyList() : List.copyOf(constraints);
    }

    public List<Constraint> getConstraints() {
        return constraints;
    }

    public static final class Constraint {
        private final String id;
        private final List<String> keywords;
        private final List<String> forbiddenCalls;
        private final List<String> requiredCalls;

        public Constraint(String id, List<String> keywords, List<String> forbiddenCalls, List<String> requiredCalls) {
            this.id = id;
            this.keywords = keywords == null ? Collections.emptyList() : List.copyOf(keywords);
            this.forbiddenCalls = forbiddenCalls == null ? Collections.emptyList() : List.copyOf(forbiddenCalls);
            this.requiredCalls = requiredCalls == null ? Collections.emptyList() : List.copyOf(requiredCalls);
        }

        public String getId() {
            return id;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public List<String> getForbiddenCalls() {
            return forbiddenCalls;
        }

        public List<String> getRequiredCalls() {
            return requiredCalls;
        }
    }
}
