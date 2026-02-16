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

package org.apache.aiv.util;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Filters file paths by glob patterns. Used for exclude_paths config.
 *
 * @author Vaquar Khan
 */
public final class PathFilter {

    private PathFilter() {}

    /**
     * Returns true if the path should be excluded (matches any exclude pattern).
     */
    public static boolean isExcluded(String relativePath, List<String> excludePatterns) {
        if (excludePatterns == null || excludePatterns.isEmpty()) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        Path path = Paths.get(normalized);
        for (String pattern : excludePatterns) {
            if (pattern == null || pattern.isBlank()) continue;
            String glob = pattern.startsWith("glob:") ? pattern : "glob:" + pattern;
            try {
                if (FileSystems.getDefault().getPathMatcher(glob).matches(path)) {
                    return true;
                }
            } catch (Exception e) {
                // Fallback: simple contains/endsWith for common patterns
                if (pattern.contains("**")) {
                    String simple = pattern.replace("**/", "").replace("/**", "");
                    if (normalized.contains(simple)) return true;
                } else if (normalized.endsWith(pattern) || normalized.contains(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Filters a list of paths, keeping only those not excluded.
     */
    public static List<String> filterExcluded(List<String> paths, List<String> excludePatterns) {
        if (excludePatterns == null || excludePatterns.isEmpty()) {
            return paths;
        }
        return paths.stream()
                .filter(p -> !isExcluded(p, excludePatterns))
                .collect(Collectors.toList());
    }
}
