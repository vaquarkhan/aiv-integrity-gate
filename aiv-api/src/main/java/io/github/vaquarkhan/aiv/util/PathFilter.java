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

package io.github.vaquarkhan.aiv.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Filters file paths by glob patterns. Used for {@code exclude_paths} in {@code .aiv/config.yaml}.
 * Each pattern is repository-relative; an optional {@code glob:} prefix is added when missing.
 * Matching uses {@link java.nio.file.FileSystem#getPathMatcher(java.lang.String)} with {@code glob:}
 * syntax (recursive segments use consecutive path wildcards). Invalid globs fall back to simple
 * substring heuristics. Negation forms and brace expansion are not supported.
 *
 * @author Vaquar Khan
 */
public final class PathFilter {

    private static final Logger log = LoggerFactory.getLogger(PathFilter.class);

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
                log.debug("Glob pattern match failed for {}: {}", pattern, e.getMessage());
            }
            // Fallback: simple contains (handles Windows path matcher quirks)
            if (pattern.contains("**")) {
                String segment = pattern.replace("**/", "").replace("/**", "").replace("**", "");
                if (!segment.isEmpty() && normalized.contains(segment)) return true;
            } else if (normalized.endsWith(pattern) || normalized.contains(pattern)) {
                return true;
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
