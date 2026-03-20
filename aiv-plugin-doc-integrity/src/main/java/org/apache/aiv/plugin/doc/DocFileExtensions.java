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

import java.util.Set;

/**
 * File extensions and names for documentation files.
 *
 * @author Vaquar Khan
 */
public final class DocFileExtensions {

    private static final Set<String> EXTENSIONS = Set.of(".md", ".txt", ".rst");
    private static final Set<String> NAMES = Set.of("AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md", "README.md");

    private DocFileExtensions() {
    }

    public static boolean matches(String path) {
        if (path == null || path.isEmpty()) return false;
        String normalized = path.replace("\\", "/");
        String base = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf("/") + 1) : normalized;
        if (NAMES.contains(base)) return true;
        String lower = path.toLowerCase();
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
