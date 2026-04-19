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

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathFabricationDetectorTest {

    private AIVContext context(Path workspace, List<ChangedFile> files) {
        var diff = new Diff("main", "HEAD", files, "", 0, 0, null, false);
        var config = new AIVConfig(List.of(), Map.of());
        return new AIVContext(workspace, diff, config);
    }

    private PathFabricationDetector detector(AIVContext ctx) {
        return new PathFabricationDetector(ctx);
    }

    @Test
    void returnsNullForNullContent() {
        var ctx = context(Path.of("."), List.of());
        var d = detector(ctx);
        assertNull(d.validate("README.md", null));
    }

    @Test
    void returnsNullWhenPathAppearsInOtherFile() {
        var ctx = context(Path.of("."), List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "See src/main/docs"),
                new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "path src/main/docs")
        ));
        var d = detector(ctx);
        assertNull(d.validate("README.md", "See src/main/docs for details"));
    }

    @Test
    void failsWhenPathOnlyInCurrentDocAndNotElsewhere(@TempDir Path dir) {
        var ctx = context(dir, List.of(
                new ChangedFile("README.md", ChangedFile.ChangeType.ADDED, "Setup at ~/.virtualenvs/pyspark")
        ));
        var d = detector(ctx);
        String result = d.validate("README.md", "Setup at ~/.virtualenvs/pyspark");
        assertNotNull(result);
        assert result.contains("virtualenvs");
    }

    @Test
    void returnsNullForShortPaths() {
        var ctx = context(Path.of("."), List.of());
        var d = detector(ctx);
        assertNull(d.validate("README.md", "See a/b"));
    }

    @Test
    void returnsNullForHttpPaths() {
        var ctx = context(Path.of("."), List.of());
        var d = detector(ctx);
        assertNull(d.validate("README.md", "Visit https://example.com/path"));
    }

    @Test
    void completesWhenWorkspacePathIsMissing(@TempDir Path dir) {
        Path missing = dir.resolve("no-such-workspace");
        var ctx = context(missing, List.of());
        var d = detector(ctx);
        assertNull(d.validate("README.md", "plain text no path pattern match"));
    }

    @Test
    void skipsUnencodedFilesDuringRepoWalk(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad-utf8.md");
        Files.write(bad, new byte[]{(byte) 0x80, (byte) 0xFF});
        var ctx = context(dir, List.of());
        var d = detector(ctx);
        assertNull(d.validate("README.md", "no embedded repo path here"));
    }
}
