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
    void fromConfigFileExtensions() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("file_extensions", List.of(".java", ".py", "go")));
        assertTrue(ext.contains(".java"));
        assertTrue(ext.contains(".py"));
        assertTrue(ext.contains(".go"));
    }

    @Test
    void fromConfigLanguages() {
        Set<String> ext = FileExtensions.fromConfig(Map.of("languages", List.of("java", "python")));
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
}
