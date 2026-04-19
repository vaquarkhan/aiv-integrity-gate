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

package io.github.vaquarkhan.aiv.plugin.dependency;

import io.github.vaquarkhan.aiv.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGateTest {

    @Test
    void getId() {
        assertEquals("dependency", new DependencyGate().getId());
    }

    @Test
    void passesWhenNoJavaOrPythonFiles() {
        var gate = new DependencyGate();
        var ctx = context(List.of(new ChangedFile("readme.md", ChangedFile.ChangeType.ADDED, "# doc")));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenJavaImportInPom(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>app</artifactId>
              <dependencies>
                <dependency><groupId>org.apache.iceberg</groupId><artifactId>iceberg-api</artifactId></dependency>
              </dependencies>
            </project>
            """);
        var gate = new DependencyGate();
        var ctx = context(dir, List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED,
                "import org.apache.iceberg.Table;\nclass Main {}")));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsWhenJavaImportNotInPom(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>app</artifactId>
              <dependencies>
                <dependency><groupId>org.apache.iceberg</groupId><artifactId>iceberg-api</artifactId></dependency>
              </dependencies>
            </project>
            """);
        var gate = new DependencyGate();
        var ctx = context(dir, List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED,
                "import com.fake.attacker.Evil;\nclass Main {}")));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("not covered by declared dependencies"));
    }

    @Test
    void passesWhenGuavaImportMatchesKnownMapping(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>app</artifactId>
              <dependencies>
                <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
              </dependencies>
            </project>
            """);
        var gate = new DependencyGate();
        var ctx = context(dir, List.of(new ChangedFile("U.java", ChangedFile.ChangeType.ADDED,
                "import com.google.common.collect.ImmutableList;\nclass U { void x() { ImmutableList.of(); } }")));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void extractGroupIdPrefersProjectAfterParent() {
        String xml = """
                <project>
                <parent><groupId>org.springframework.boot</groupId></parent>
                <groupId>com.example.app</groupId>
                </project>
                """;
        assertEquals("com.example.app", DependencyGate.extractGroupId(xml));
    }

    @Test
    void extractGroupIdReturnsNullWhenNoProjectGroupId() {
        assertNull(DependencyGate.extractGroupId("<project><artifactId>only</artifactId></project>"));
    }

    @Test
    void skipsJavaFileWhenSourceDoesNotParse(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>app</artifactId>
            </project>
            """);
        var gate = new DependencyGate();
        var ctx = context(dir, List.of(new ChangedFile("Broken.java", ChangedFile.ChangeType.ADDED, "class {")));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenPythonImportInRequirements(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("requirements.txt"), "flask\nrequests\n");
        var gate = new DependencyGate();
        var ctx = context(dir, List.of(new ChangedFile("app.py", ChangedFile.ChangeType.ADDED,
                "import flask\nfrom requests import get\n")));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsWhenPythonImportNotInRequirements(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("requirements.txt"), "flask\n");
        var gate = new DependencyGate();
        var ctx = context(dir, List.of(new ChangedFile("app.py", ChangedFile.ChangeType.ADDED,
                "import fake_attacker\n")));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("requirements.txt"));
    }

    @Test
    void passesWithWhitelist() {
        var gate = new DependencyGate();
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("dependency", true, Map.of("whitelist", List.of("com.fake.attacker")))),
                Map.of());
        var diff = new Diff("main", "HEAD", List.of(new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED,
                "import com.fake.attacker.Evil;\nclass Main {}")), "");
        var ctx = new AIVContext(Path.of("."), diff, config);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenPythonImportInPyprojectToml(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pyproject.toml"), """
            [project]
            dependencies = ["flask>=1.0", "requests"]
            """);
        var gate = new DependencyGate();
        var ctx = context(dir, List.of(new ChangedFile("app.py", ChangedFile.ChangeType.ADDED,
                "import flask\nfrom requests import get\n")));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenDependencyFilesUnreadable(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("pom.xml"));
        Files.createDirectory(dir.resolve("requirements.txt"));

        var gate = new DependencyGate();
        var ctx = context(dir, List.of(
                new ChangedFile("Main.java", ChangedFile.ChangeType.ADDED, "import com.fake.attacker.Evil;\nclass Main {}"),
                new ChangedFile("app.py", ChangedFile.ChangeType.ADDED, "import fake_attacker\n")
        ));
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    private AIVContext context(List<ChangedFile> files) {
        return context(Path.of("."), files);
    }

    private AIVContext context(Path workspace, List<ChangedFile> files) {
        var diff = new Diff("main", "HEAD", files, "");
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig("dependency", true, Map.of())), Map.of());
        return new AIVContext(workspace, diff, config);
    }
}
