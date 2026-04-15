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

    /**
     * {@code workspace.resolve(docPath).getParent()} is null for a volume-root style docPath, so
     * {@code baseDir} stays the workspace (see {@code CrossReferenceChecker#resolveExists}).
     */
    @Test
    void resolvesFromWorkspaceWhenDocPathHasNoParent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/README.md"), "x");
        var c = new CrossReferenceChecker(dir);
        Path root = dir.getRoot();
        assertTrue(root != null);
        String docPath = root.toString().replace('\\', '/');
        if (!docPath.contains("/")) {
            docPath = docPath + "/";
        } else if (!docPath.endsWith("/") && docPath.length() > 1) {
            docPath = docPath + "/";
        }
        assertTrue(docPath.contains("/"));
        assertNull(c.validate(docPath, "Read the README in nested"));
    }

    @Test
    void referToRelativePathOutsideThenInside(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("deep/nest/here"));
        Files.writeString(dir.resolve("target.txt"), "found");
        var c = new CrossReferenceChecker(dir);
        assertNull(c.validate("deep/nest/here/doc.md",
                "Refer to ../../../target.txt for details."));
    }

    @Test
    void resolveExistsUsesWorkspaceRootWhenPrimaryPathEscapes(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("a/b/c"));
        var c = new CrossReferenceChecker(dir);
        assertFalse(c.resolveExists("../../../../no-such-file-" + System.nanoTime() + ".txt", "a/b/c/x.md"));
    }
}
