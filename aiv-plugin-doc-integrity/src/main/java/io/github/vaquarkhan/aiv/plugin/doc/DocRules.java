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

import java.util.Collections;
import java.util.List;

/**
 * Parsed doc rules from YAML.
 *
 * @author Vaquar Khan
 */
public final class DocRules {

    private final List<DocConstraint> docConstraints;
    private final List<CanonicalCommand> canonicalCommands;

    public DocRules(List<DocConstraint> docConstraints, List<CanonicalCommand> canonicalCommands) {
        this.docConstraints = docConstraints == null ? Collections.emptyList() : List.copyOf(docConstraints);
        this.canonicalCommands = canonicalCommands == null ? Collections.emptyList() : List.copyOf(canonicalCommands);
    }

    public List<DocConstraint> getDocConstraints() {
        return docConstraints;
    }

    public List<CanonicalCommand> getCanonicalCommands() {
        return canonicalCommands;
    }

    public static final class DocConstraint {
        private final String id;
        private final List<String> triggerKeywords;
        private final List<String> requiredMentions;
        private final List<String> scope;

        public DocConstraint(String id, List<String> triggerKeywords, List<String> requiredMentions, List<String> scope) {
            this.id = id;
            this.triggerKeywords = triggerKeywords == null ? Collections.emptyList() : List.copyOf(triggerKeywords);
            this.requiredMentions = requiredMentions == null ? Collections.emptyList() : List.copyOf(requiredMentions);
            this.scope = scope == null ? Collections.emptyList() : List.copyOf(scope);
        }

        public String getId() { return id; }
        public List<String> getTriggerKeywords() { return triggerKeywords; }
        public List<String> getRequiredMentions() { return requiredMentions; }
        public List<String> getScope() { return scope; }
    }

    public static final class CanonicalCommand {
        private final String id;
        private final String pattern;
        private final List<String> requiredFlags;
        private final List<String> requiredFollowup;
        private final List<String> requiredCommands;

        public CanonicalCommand(String id, String pattern, List<String> requiredFlags,
                               List<String> requiredFollowup, List<String> requiredCommands) {
            this.id = id;
            this.pattern = pattern;
            this.requiredFlags = requiredFlags == null ? Collections.emptyList() : List.copyOf(requiredFlags);
            this.requiredFollowup = requiredFollowup == null ? Collections.emptyList() : List.copyOf(requiredFollowup);
            this.requiredCommands = requiredCommands == null ? Collections.emptyList() : List.copyOf(requiredCommands);
        }

        public String getId() { return id; }
        public String getPattern() { return pattern; }
        public List<String> getRequiredFlags() { return requiredFlags; }
        public List<String> getRequiredFollowup() { return requiredFollowup; }
        public List<String> getRequiredCommands() { return requiredCommands; }
    }
}
