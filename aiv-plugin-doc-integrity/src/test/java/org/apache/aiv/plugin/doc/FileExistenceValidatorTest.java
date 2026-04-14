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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void resolvesTildePrefixAsRepoRelative(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("cfg/sub"));
        Files.writeString(dir.resolve("cfg/sub/x.txt"), "x");
        var v = new FileExistenceValidator(dir);
        assertNull(v.validate("README.md", "Edit ~/cfg/sub/x.txt"));
    }

    @Test
    void resolvesLeadingSlashAsRepoRelative(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("logs/app"));
        Files.writeString(dir.resolve("logs/app/out.txt"), "x");
        var v = new FileExistenceValidator(dir);
        assertNull(v.validate("README.md", "Tail /logs/app/out.txt"));
    }

    @Test
    void usesWorkspaceWhenDocPathHasNoFileParent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/f.txt"), "x");
        var v = new FileExistenceValidator(dir);
        Path root = dir.getRoot();
        assertNotNull(root);
        String docPath = root.toString().replace('\\', '/');
        if (!docPath.contains("/")) {
            docPath = docPath + "/";
        } else if (!docPath.endsWith("/") && docPath.length() > 1) {
            docPath = docPath + "/";
        }
        assertTrue(docPath.contains("/"));
        assertNull(v.validate(docPath, "nested/f.txt"));
    }

    @Test
    void resolveExistsUsesWorkspaceRootWhenPrimaryPathEscapes(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("a/b/c"));
        var v = new FileExistenceValidator(dir);
        assertFalse(v.resolveExists("../../../../no-such-" + System.nanoTime() + ".log", "a/b/c/x.md"));
    }
}
