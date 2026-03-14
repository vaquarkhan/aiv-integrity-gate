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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileExtensionsTest {

    @Test
    void fromConfigEmptyReturnsDefaults() {
        Set<String> ext = FileExtensions.fromConfig(Map.of());
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".py"));
        assertTrue(ext.contains(".go"));
    }

    @Test
    void fromConfigNullReturnsDefaults() {
        Set<String> ext = FileExtensions.fromConfig(null);
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".py"));
    }

    @Test
    void fromConfigFileExtensions() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("file_extensions", List.of(".java", ".py", "go")));
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".py"));
        assertTrue(ext.contains(".go"));
    }

    @Test
    void fromConfigFileExtensionsIgnoresNonStrings() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("file_extensions", Arrays.asList(".java", 1, null)));
        assertTrue(ext.contains(".java"));
        assertFalse(ext.contains(".1"));
    }

    @Test
    void fromConfigFileExtensionsEmptyListReturnsDefaults() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("file_extensions", List.of()));
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".py"));
    }

    @Test
    void fromConfigLanguages() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("languages", List.of("java", "python")));
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".py"));
    }

    @Test
    void fromConfigLanguagesCoversAliasesAndUnknown() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("languages", List.of("kt", "typescript", "c++", "shell", "weirdlang")));
        assertTrue(ext.contains(".kt"));
        assertTrue(ext.contains(".ts"));
        assertTrue(ext.contains(".cpp"));
        assertTrue(ext.contains(".sh"));
        assertTrue(ext.contains(".weirdlang"));
    }

    @Test
    void fromConfigLanguagesCoversAllSwitchCases() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("languages", List.of(
                "java", "kotlin", "python", "go", "rust", "scala", "javascript", "c", "ruby", "bash"
        )));
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".kt"));
        assertTrue(ext.contains(".py"));
        assertTrue(ext.contains(".go"));
        assertTrue(ext.contains(".rs"));
        assertTrue(ext.contains(".scala"));
        assertTrue(ext.contains(".js"));
        assertTrue(ext.contains(".c"));
        assertTrue(ext.contains(".rb"));
        assertTrue(ext.contains(".bash"));
    }

    @Test
    void fromConfigUnknownConfigReturnsDefaults() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("unknown_key", List.of(".md")));
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".py"));
    }

    @Test
    void matches() {
        Set<String> ext = Set.of(".java", ".py");
        assertTrue(FileExtensions.matches("src/Foo.java", ext));
        assertTrue(FileExtensions.matches("bar.PY", ext));
        assertFalse(FileExtensions.matches("readme.md", ext));
        assertFalse(FileExtensions.matches("", ext));
    }

    @Test
    void matchesNullOrEmptyInputsReturnFalse() {
        assertFalse(FileExtensions.matches(null, Set.of(".java")));
        assertFalse(FileExtensions.matches("src/Foo.java", null));
        assertFalse(FileExtensions.matches("src/Foo.java", Set.of()));
    }
}
