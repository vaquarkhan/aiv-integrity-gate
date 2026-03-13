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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFilterTest {

    @Test
    void excludeGenerated() {
        assertTrue(PathFilter.isExcluded("src/main/java/generated/foo.java", List.of("**/generated/**")));
        assertTrue(PathFilter.isExcluded("target/generated-sources/bar.java", List.of("**/generated/**")));
        assertFalse(PathFilter.isExcluded("src/main/java/com/example/App.java", List.of("**/generated/**")));
    }

    @Test
    void excludeProtoGenerated() {
        assertTrue(PathFilter.isExcluded("src/main/java/com/example/Service.pb.java", List.of("**/*.pb.java")));
        assertFalse(PathFilter.isExcluded("src/main/java/com/example/Service.java", List.of("**/*.pb.java")));
    }

    @Test
    void emptyPatterns() {
        assertFalse(PathFilter.isExcluded("any/path.java", List.of()));
        assertFalse(PathFilter.isExcluded("any/path.java", null));
    }

    @Test
    void globAndFallbackPatterns() {
        assertTrue(PathFilter.isExcluded("src/main/java/com/example/Service.pb.java", List.of("glob:**/*.pb.java")));
        assertTrue(PathFilter.isExcluded("src\\main\\java\\com\\example\\Service.pb.java", List.of("**/*.pb.java")));

        // Invalid glob triggers fallback; segment extracted from pattern should still match.
        assertTrue(PathFilter.isExcluded("src/[test].java", List.of("**[")));

        // Non-** fallback: endsWith/contains paths.
        assertTrue(PathFilter.isExcluded("src/main/java/com/example/App.java", List.of("App.java")));
        assertTrue(PathFilter.isExcluded("src/main/java/com/example/App.java", List.of("com/example")));

        // Null/blank patterns should be ignored.
        assertFalse(PathFilter.isExcluded("src/main/java/com/example/App.java", Arrays.asList(null, "", "  ")));
    }

    @Test
    void filterExcludedKeepsOnlyIncluded() {
        List<String> input = List.of("a.java", "generated/x.java", "b.py");
        List<String> out = PathFilter.filterExcluded(input, List.of("**/generated/**"));
        assertFalse(out.contains("generated/x.java"));
        assertTrue(out.contains("a.java"));
        assertTrue(out.contains("b.py"));
    }

    @Test
    void filterExcludedWithNoPatternsReturnsInputList() {
        List<String> input = List.of("a.java", "b.java");
        assertSame(input, PathFilter.filterExcluded(input, null));
        assertSame(input, PathFilter.filterExcluded(input, List.of()));
    }
}
