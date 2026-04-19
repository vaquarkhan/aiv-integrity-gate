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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownLinkCheckerTest {

    @Test
    void slugifyHeadingMatchesGitHubStyle() {
        assertEquals("running-individual-tests", MarkdownLinkChecker.slugifyHeading("Running Individual Tests"));
        assertEquals("foo", MarkdownLinkChecker.slugifyHeading("FOO!!!"));
        assertEquals("a", MarkdownLinkChecker.slugifyHeading("---a"));
        assertEquals("b", MarkdownLinkChecker.slugifyHeading("b---"));
    }

    @Test
    void passesWhenRelativeTargetExists(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/target.md"), "# Hi\n");
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("README.md", "See [t](docs/target.md)"));
    }

    @Test
    void failsWhenTargetMissing(@TempDir Path root) throws Exception {
        var checker = new MarkdownLinkChecker(root);
        String v = checker.validate("README.md", "See [t](docs/nope.md)");
        assertNotNull(v);
        assertTrue(v.contains("does not exist"));
    }

    @Test
    void skipsExternalHttp(@TempDir Path root) {
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("README.md", "[a](https://example.com/x)"));
    }

    @Test
    void anchorPassesWhenHeadingPresent(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/other.md"), "## Running Individual Tests\n\nbody\n");
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("AGENTS.md", "See [x](docs/other.md#running-individual-tests)"));
    }

    @Test
    void anchorFailsWhenHeadingMissing(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/other.md"), "## Something Else\n");
        var checker = new MarkdownLinkChecker(root);
        String v = checker.validate("README.md", "See [x](docs/other.md#running-individual-tests)");
        assertNotNull(v);
        assertTrue(v.contains("fragment"));
    }

    @Test
    void returnsNullForNullOrEmptyContent(@TempDir Path root) {
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("README.md", null));
        assertNull(checker.validate("README.md", ""));
    }

    @Test
    void skipsFragmentOnlyAndEmptyTargets(@TempDir Path root) {
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("a.md", "[x](#only-fragment)"));
        assertNull(checker.validate("a.md", "[x]()"));
        assertNull(checker.validate("a.md", "[x](   )"));
    }

    @Test
    void stripsOptionalLinkTitle(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("d"));
        Files.writeString(root.resolve("d/t.md"), "# OK\n");
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("z.md", "[l](d/t.md \"title\")"));
    }

    @Test
    void skipsMailtoFtpAndProtocolRelative(@TempDir Path root) {
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("a.md", "[m](mailto:a@b)"));
        assertNull(checker.validate("a.md", "[f](ftp://h/x)"));
        assertNull(checker.validate("a.md", "[c](//example/x)"));
    }

    @Test
    void failsWhenLinkEscapesWorkspace(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/page.md"), "x");
        var checker = new MarkdownLinkChecker(root);
        String v = checker.validate("sub/page.md", "[o](../../outside.md)");
        assertNotNull(v);
        assertTrue(v.contains("escapes workspace"));
    }

    @Test
    void resolvesWindowsStyleSeparatorsInRelativeTarget(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/nested.md"), "# N\n");
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("README.md", "[n](docs\\\\nested.md)"));
    }

    @Test
    void anchorCheckReportsErrorWhenTargetIsNotUtf8(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("d"));
        Path md = root.resolve("d/x.md");
        Files.write(md, new byte[]{(byte) 0x80, (byte) 0xFF});
        var checker = new MarkdownLinkChecker(root);
        String v = checker.validate("README.md", "[l](d/x.md#any)");
        assertNotNull(v);
        assertTrue(v.contains("could not read"));
    }

    @Test
    void skipsWhenPathPartEmptyAfterFragmentSplit(@TempDir Path root) {
        var checker = new MarkdownLinkChecker(root);
        assertNull(checker.validate("README.md", "[l]( #only-fragment)"));
    }
}
