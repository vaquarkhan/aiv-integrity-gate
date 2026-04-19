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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocFileExtensionsTest {

    @Test
    void matchesMd() {
        assertTrue(DocFileExtensions.matches("README.md"));
        assertTrue(DocFileExtensions.matches("docs/guide.md"));
        assertTrue(DocFileExtensions.matches("a/b/c.MD"));
    }

    @Test
    void matchesTxt() {
        assertTrue(DocFileExtensions.matches("notes.txt"));
        assertTrue(DocFileExtensions.matches("a.TXT"));
    }

    @Test
    void matchesRst() {
        assertTrue(DocFileExtensions.matches("index.rst"));
        assertTrue(DocFileExtensions.matches("docs/readme.RST"));
    }

    @Test
    void matchesSpecialNames() {
        assertTrue(DocFileExtensions.matches("AGENTS.md"));
        assertTrue(DocFileExtensions.matches("CLAUDE.md"));
        assertTrue(DocFileExtensions.matches("CONTRIBUTING.md"));
        assertTrue(DocFileExtensions.matches("README.md"));
        assertTrue(DocFileExtensions.matches("docs/AGENTS.md"));
    }

    @Test
    void rejectsNonDoc() {
        assertFalse(DocFileExtensions.matches("Foo.java"));
        assertFalse(DocFileExtensions.matches("main.py"));
        assertFalse(DocFileExtensions.matches("script.sh"));
        assertFalse(DocFileExtensions.matches("config.yaml"));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertFalse(DocFileExtensions.matches(null));
        assertFalse(DocFileExtensions.matches(""));
    }
}
