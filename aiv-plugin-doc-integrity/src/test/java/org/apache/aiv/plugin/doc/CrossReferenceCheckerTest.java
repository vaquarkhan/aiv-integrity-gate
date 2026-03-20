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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CrossReferenceCheckerTest {

    @Test
    void returnsNullForNullContent() {
        var c = new CrossReferenceChecker(Path.of("."));
        assertNull(c.validate("README.md", null));
    }

    @Test
    void returnsNullWhenRefExists(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/README.md"), "content");
        var c = new CrossReferenceChecker(dir);
        assertNull(c.validate("README.md", "Read the README in docs"));
    }

    @Test
    void failsWhenReadmeRefDoesNotExist(@TempDir Path dir) {
        var c = new CrossReferenceChecker(dir);
        String result = c.validate("README.md", "Read the README in nonexistent/folder");
        assertNotNull(result);
    }

    @Test
    void returnsNullWhenNoCrossRefPhrases() {
        var c = new CrossReferenceChecker(Path.of("."));
        assertNull(c.validate("README.md", "Just some text without cross-refs"));
    }
}
