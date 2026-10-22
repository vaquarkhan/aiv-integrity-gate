/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.pkgexist;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageExistenceGateTest {

    /** Registry stub: names containing "ghost" do not exist, names containing "down" are unreachable. */
    private static final RegistryClient STUB = (eco, name) -> {
        if (name.contains("down")) {
            return Optional.empty();
        }
        return Optional.of(!name.contains("ghost"));
    };

    private static AIVContext ctx(Map<String, Object> cfg, String rawDiff, ChangedFile... files) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(PackageExistenceGate.ID, true, cfg)), Map.of());
        return new AIVContext(Path.of("."), new Diff("main", "HEAD", List.of(files), rawDiff), config);
    }

    private static PackageExistenceGate gate() {
        return new PackageExistenceGate(cfg -> STUB);
    }

    @Test
    void registeredAndDefaultConstructorBuildsHttpClient() {
        assertTrue(ServiceLoader.load(QualityGate.class).stream().anyMatch(p -> p.type() == PackageExistenceGate.class));
        assertEquals("package-existence", new PackageExistenceGate().getId());
        RegistryClient c = PackageExistenceGate.defaultClient(Map.of("timeout_seconds", 1, "pypi_url", "http://127.0.0.1:9/x"));
        assertTrue(c instanceof HttpRegistryClient);
        assertTrue(PackageExistenceGate.defaultClient(Map.of()) instanceof HttpRegistryClient);
    }

    @Test
    void hallucinatedRequirementFailsHard() {
        String req = "requests>=2.0\nghost-tensor-utils==1.0\n# comment\n-r base.txt\n";
        GateResult r = gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("requirements.txt", ChangedFile.ChangeType.ADDED, req)));
        assertFalse(r.isPassed());
        assertTrue(r.blocksCi());
        assertEquals(1, r.getFindings().size());
        assertEquals(PackageExistenceGate.RULE_UNKNOWN, r.getFindings().get(0).getRuleId());
        assertEquals(2, r.getFindings().get(0).getStartLine());
        assertTrue(r.getMessage().contains("ghost-tensor-utils"));
    }

    @Test
    void confirmedDependenciesPassWithCount() {
        GateResult r = gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("requirements-dev.txt", ChangedFile.ChangeType.ADDED, "pytest\nruff[all]>=1\n")));
        assertTrue(r.isPassed());
        assertTrue(r.getMessage().contains("2 newly declared dependencies confirmed"));
        GateResult one = gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("requirements.txt", ChangedFile.ChangeType.ADDED, "pytest\n")));
        assertTrue(one.getMessage().contains("1 newly declared dependency confirmed"));
    }

    @Test
    void unreachableRegistryIsReportedNotHidden() {
        ChangedFile f = new ChangedFile("requirements.txt", ChangedFile.ChangeType.ADDED, "downlib\nrequests\n");
        GateResult r = gate().evaluate(ctx(Map.of(), "", f));
        assertTrue(r.isPassed());
        assertTrue(r.getMessage().startsWith("PASS with 1 UNVERIFIED"));
        assertEquals(PackageExistenceGate.RULE_UNVERIFIED, r.getFindings().get(0).getRuleId());
        GateResult strict = gate().evaluate(ctx(Map.of("strict", true), "", f));
        assertFalse(strict.isPassed());
        assertTrue(strict.blocksCi());
        // unknown + unverified together: fail carries both findings
        GateResult both = gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("requirements.txt", ChangedFile.ChangeType.ADDED, "downlib\nghostlib\n")));
        assertEquals(2, both.getFindings().size());
    }

    @Test
    void onlyAddedLinesOfModifiedManifestsAreChecked() {
        String req = "ghost-old==1\nrequests\nghost-new==2\n";
        String rawDiff = "--- a/requirements.txt\n+++ b/requirements.txt\n@@ -2,0 +3 @@\n+ghost-new==2\n";
        GateResult r = gate().evaluate(ctx(Map.of(), rawDiff,
                new ChangedFile("requirements.txt", ChangedFile.ChangeType.MODIFIED, req),
                new ChangedFile("other/requirements.txt", ChangedFile.ChangeType.MODIFIED, "ghost-x\n")));
        assertEquals(1, r.getFindings().size());
        assertEquals(3, r.getFindings().get(0).getStartLine());
        // Without any diff index a MODIFIED manifest is scanned whole.
        GateResult whole = gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("requirements.txt", ChangedFile.ChangeType.MODIFIED, req)));
        assertEquals(2, whole.getFindings().size());
    }

    @Test
    void allowListAndNonManifestsAreSkipped() {
        GateResult r = gate().evaluate(ctx(Map.of("allow", List.of("Ghost-Lib")), "",
                new ChangedFile("requirements.txt", ChangedFile.ChangeType.ADDED, "ghost-lib\n"),
                new ChangedFile("src/app.py", ChangedFile.ChangeType.ADDED, "import ghost\n")));
        assertTrue(r.isPassed());
        assertEquals("OK", r.getMessage());
        var context = new AIVContext(Path.of("."), new Diff("a", "b", List.of(
                new ChangedFile("package.json", ChangedFile.ChangeType.ADDED,
                        "{\n  \"name\": \"app\",\n  \"dependencies\": {\n    \"@scope/ghost-ui\": \"^1.0.0\"\n  }\n}\n")), ""),
                new AIVConfig(List.of(), Map.of()));
        GateResult npm = gate().evaluate(context);
        assertFalse(npm.isPassed());
        assertTrue(npm.getMessage().contains("@scope/ghost-ui"));
        assertTrue(npm.getMessage().contains("npm registry"));
    }

    @Test
    void pomDependenciesAreExtractedByArtifactLine() {
        String pom = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>org.apache.commons</groupId>
                  <artifactId>commons-lang3</artifactId>
                </dependency>
                <dependency>
                  <groupId>io.ghost</groupId>
                  <artifactId>ghost-core</artifactId>
                  <version>1.0</version>
                </dependency>
                <dependency>
                  <groupId>${project.groupId}</groupId>
                  <artifactId>internal</artifactId>
                </dependency>
                <dependency>
                  <artifactId>no-group</artifactId>
                </dependency>
                <dependency>
                  <groupId>io.ghost</groupId>
                  <artifactId>ghost-core</artifactId>
                </dependency>
              </dependencies>
            </project>
            """;
        String rawDiff = "--- a/pom.xml\n+++ b/pom.xml\n@@ -0,0 +1 @@\n+      <artifactId>ghost-core</artifactId>\n";
        GateResult r = gate().evaluate(ctx(Map.of(), rawDiff,
                new ChangedFile("pom.xml", ChangedFile.ChangeType.MODIFIED, pom)));
        assertEquals(1, r.getFindings().size());
        assertEquals(9, r.getFindings().get(0).getStartLine());
        assertTrue(r.getMessage().contains("io.ghost:ghost-core"));
        // whole-file scan (ADDED pom) confirms commons-lang3, flags ghost-core once
        GateResult added = gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("sub/pom.xml", ChangedFile.ChangeType.ADDED, pom)));
        assertEquals(1, added.getFindings().size());
    }

    @Test
    void pyprojectAndEmptyManifests() {
        String toml = """
            [build-system]
            requires = ["hatchling"]
            [project]
            name = "x"
            dependencies = [
              "httpx>=0.27",
              "ghost-sdk",
            ]
            [tool.ruff.lint]
            extend-select = [
                "COM812",
                "ghost-rule",
            ]
            [project.optional-dependencies]
            dev = [   # comment
              "ghost-dev"]
            [dependency-groups]
            test = [
              "pytest",
            ]
            [build-system]
            requires = [
              "ghost-build",
            ]
            """;
        GateResult r = gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("pyproject.toml", ChangedFile.ChangeType.ADDED, toml)));
        // ghost-sdk (project.dependencies), ghost-dev (optional), ghost-build (build-system); NOT the ruff codes
        assertEquals(3, r.getFindings().size());
        assertEquals(7, r.getFindings().get(0).getStartLine());
        assertTrue(r.getMessage().contains("ghost-dev"));
        assertTrue(r.getMessage().contains("ghost-build"));
        assertFalse(r.getMessage().contains("ghost-rule"), "ruff rule codes are not packages");
        assertTrue(gate().evaluate(ctx(Map.of(), "",
                new ChangedFile("pyproject.toml", ChangedFile.ChangeType.ADDED, "  "))).isPassed());
        assertTrue(gate().evaluate(ctx(Map.of(), "")).isPassed());
    }

    @Test
    void declarationHelpers() {
        assertNull(DependencyDeclarations.requirementName("https://x/y.whl"));
        assertNull(DependencyDeclarations.requirementName("./local"));
        assertNull(DependencyDeclarations.requirementName("/abs/path"));
        assertNull(DependencyDeclarations.requirementName("C:\\win\\path"));
        assertNull(DependencyDeclarations.requirementName("pkg @ file:///x"));
        assertNull(DependencyDeclarations.requirementName("--index-url x"));
        assertNull(DependencyDeclarations.requirementName("=== weird"));
        assertEquals("Foo_Bar.baz", DependencyDeclarations.requirementName("Foo_Bar.baz[extra]==1 ; python_version<'3'"));
        assertTrue(DependencyDeclarations.isManifest("a/requirements-test.txt"));
        assertTrue(DependencyDeclarations.isManifest("pom.xml"));
        assertFalse(DependencyDeclarations.isManifest("requirements.md"));
        assertEquals("", DependencyDeclarations.basename(null));
        assertTrue(DependencyDeclarations.extract("requirements.txt", null, Set.of(), true).isEmpty());
        assertEquals("", PackageExistenceGate.norm(null));
        Map<String, Set<String>> idx = PackageExistenceGate.addedLinesByPath(
                "+++ b/a.txt\n+x\n+++ /dev/null\n+y\n+++ b\\dir\\b.txt\n+z\n");
        assertEquals(Set.of("x"), idx.get("a.txt"));
        assertEquals(Set.of("z"), idx.get("b/dir/b.txt"));
        assertTrue(PackageExistenceGate.addedLinesByPath(" ").isEmpty());
    }

    @Test
    void httpClientInterpretsStatusesAndBuildsUrls() {
        List<String> seen = new ArrayList<>();
        HttpRegistryClient.StatusFetcher fetcher = url -> {
            seen.add(url);
            if (url.contains("missing")) {
                return 404;
            }
            if (url.contains("flaky")) {
                return 503;
            }
            if (url.contains("boom")) {
                throw new IllegalStateException("x");
            }
            return 200;
        };
        HttpRegistryClient c = new HttpRegistryClient(null, "https://npm.example/", "https://mvn.example", fetcher);
        assertEquals(Optional.of(true), c.exists("pypi", "Requests_Lib.Name"));
        assertEquals("https://pypi.org/simple/requests-lib-name/", seen.get(0));
        assertEquals(Optional.of(false), c.exists("npm", "@scope/missing"));
        assertEquals("https://npm.example/@scope%2Fmissing", seen.get(1));
        assertEquals(Optional.empty(), c.exists("npm", "flaky"));
        assertEquals(Optional.empty(), c.exists("npm", "boom"));
        assertEquals(Optional.of(true), c.exists("maven", "org.apache.commons:commons-lang3"));
        assertEquals("https://mvn.example/org/apache/commons/commons-lang3/maven-metadata.xml", seen.get(4));
        assertEquals(Optional.empty(), c.exists("maven", "nocolon"));
        assertEquals(Optional.empty(), c.exists("maven", "trailing:"));
        assertEquals(Optional.empty(), c.exists("cargo", "x"));
        assertEquals("%40bad", HttpRegistryClient.encodeNpm("@bad"));
        assertEquals("a%40b", HttpRegistryClient.encodeNpm("a@b"));
    }

    @Test
    void defaultFetcherTalksHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int code = ex.getRequestURI().getPath().contains("ghost") ? 404 : 200;
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            HttpRegistryClient c = new HttpRegistryClient(base, base, base, Duration.ofSeconds(5));
            assertEquals(Optional.of(true), c.exists("pypi", "requests"));
            assertEquals(Optional.of(false), c.exists("npm", "ghost-ui"));
            HttpRegistryClient nullTimeout = new HttpRegistryClient(base, base, base, (Duration) null);
            assertEquals(Optional.of(true), c.exists("maven", "a.b:c"));
            assertEquals(Optional.of(true), nullTimeout.exists("pypi", "x"));
        } finally {
            server.stop(0);
        }
        // connection refused -> unknown
        HttpRegistryClient dead = new HttpRegistryClient("http://127.0.0.1:1/", null, null, Duration.ofMillis(500));
        assertEquals(Optional.empty(), dead.exists("pypi", "x"));
    }
}
