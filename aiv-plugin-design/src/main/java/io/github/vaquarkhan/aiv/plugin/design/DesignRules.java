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
