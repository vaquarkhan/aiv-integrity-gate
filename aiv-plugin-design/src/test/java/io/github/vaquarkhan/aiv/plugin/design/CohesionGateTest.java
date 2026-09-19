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

package io.github.vaquarkhan.aiv.plugin.design;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CohesionGateTest {

    @Test
    void getId() {
        assertEquals("cohesion", new CohesionGate().getId());
    }

    @Test
    void areaOfUsesDirectoryDepth() {
        assertEquals("(root)", CohesionGate.areaOf("README.md", 2));
        assertEquals(".github", CohesionGate.areaOf(".github/workflows/ci.yml", 1));
        assertEquals(".github/workflows", CohesionGate.areaOf(".github/workflows/ci.yml", 2));
        assertEquals("scripts/ci", CohesionGate.areaOf("scripts/ci/prek_cache_key.py", 2));
        assertEquals("scripts/tests", CohesionGate.areaOf("scripts/tests/ci/test_x.py", 2));
    }

    @Test
    void passesWhenWithinMaxAreas() {
        var gate = new CohesionGate();
        var ctx = context(List.of(
                file(".github/workflows/a.yml"),
                file(".github/workflows/b.yml"),
                file(".github/actions/x/action.yml")
        ), Map.of("max_areas", 3, "area_depth", 2));
        // areas: .github/workflows, .github/actions = 2
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void failsOnMultiConcernSprawlLikeAirflow73124() {
        // Generic shape of apache/airflow#73124: workflows + actions + scripts/ci + tests + tools
        var gate = new CohesionGate();
        var ctx = context(List.of(
                file(".github/actions/install-prek/action.yml"),
                file(".github/workflows/basic-tests.yml"),
                file(".github/workflows/run-unit-tests.yml"),
                file("scripts/ci/prek_cache_key.py"),
                file("scripts/tests/ci/test_ci_setup_optimizations.py"),
                file("scripts/tools/free_up_disk_space.sh"),
                file("scripts/ci/make_mnt_writeable.sh")
        ), Map.of("max_areas", 3, "area_depth", 2));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("path areas"));
        assertTrue(r.getFindings().stream().anyMatch(f -> "cohesion.areas".equals(f.getRuleId())));
        Set<String> areas = CohesionGate.areasOf(List.of(
                ".github/actions/install-prek/action.yml",
                ".github/workflows/basic-tests.yml",
                "scripts/ci/prek_cache_key.py",
                "scripts/tests/ci/test_ci_setup_optimizations.py",
                "scripts/tools/free_up_disk_space.sh"
        ), 2);
        assertTrue(areas.size() > 3);
    }

    @Test
    void failsOnMaxFiles() {
        var gate = new CohesionGate();
        var ctx = context(List.of(
                file("a/x.java"),
                file("a/y.java"),
                file("a/z.java")
        ), Map.of("max_areas", 10, "max_files", 2));
        GateResult r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("max_files"));
    }

    @Test
    void defaultsPassFocusedPr() {
        var gate = new CohesionGate();
        var ctx = context(List.of(
                file("aiv-core/src/main/java/Foo.java"),
                file("aiv-core/src/test/java/FooTest.java")
        ), Map.of());
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    private static ChangedFile file(String path) {
        return new ChangedFile(path, ChangedFile.ChangeType.MODIFIED, "x\n");
    }

    private static AIVContext context(List<ChangedFile> files, Map<String, Object> cohesionConfig) {
        var diff = new Diff("main", "HEAD", files, "");
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("cohesion", true, cohesionConfig)),
                Map.of());
        return new AIVContext(Paths.get("."), diff, config);
    }
}
