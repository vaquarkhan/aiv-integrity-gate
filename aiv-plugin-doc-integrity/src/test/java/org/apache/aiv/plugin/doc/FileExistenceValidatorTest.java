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

class FileExistenceValidatorTest {

    @Test
    void returnsNullForNullContent() {
        var v = new FileExistenceValidator(Path.of("."));
        assertNull(v.validate("README.md", null));
    }

    @Test
    void returnsNullForEmptyContent() {
        var v = new FileExistenceValidator(Path.of("."));
        assertNull(v.validate("README.md", ""));
    }

    @Test
    void returnsNullWhenPathExists(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/README.md"), "content");
        Files.writeString(dir.resolve("docs/guide.md"), "guide");
        var v = new FileExistenceValidator(dir);
        assertNull(v.validate("docs/README.md", "See guide.md for details"));
    }

    @Test
    void failsWhenPathDoesNotExist(@TempDir Path dir) {
        var v = new FileExistenceValidator(dir);
        String result = v.validate("README.md", "See nonexistent/path/to/file.txt for info");
        assertNotNull(result);
    }

    @Test
    void ignoresHttpUrls() {
        var v = new FileExistenceValidator(Path.of("."));
        assertNull(v.validate("README.md", "Visit https://example.com/docs"));
    }
}
