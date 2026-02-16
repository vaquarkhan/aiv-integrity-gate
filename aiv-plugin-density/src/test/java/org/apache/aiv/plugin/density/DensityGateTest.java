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

package org.apache.aiv.plugin.density;

import org.apache.aiv.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class DensityGateTest {

    @Test
    void getId() {
        assertEquals("density", new DensityGate().getId());
    }

    @Test
    void passesWhenNoJavaFiles() {
        var gate = new DensityGate();
        var ctx = context(List.of());
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void passesWhenLdrAndEntropyAboveThreshold() {
        String code = """
            public class Foo {
                public int bar(int x) {
                    if (x > 0) return x + 1;
                    return x - 1;
                }
            }
            """;
        var gate = new DensityGate();
        var ctx = context(List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, code)));
        var r = gate.evaluate(ctx);
        assertTrue(r.isPassed());
    }

    @Test
    void failsWhenEntropyTooLow() {
        String code = "aaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        var gate = new DensityGate();
        var ctx = context(List.of(new ChangedFile("x.java", ChangedFile.ChangeType.ADDED, code)));
        var r = gate.evaluate(ctx);
        assertFalse(r.isPassed());
        assertTrue(r.getMessage().contains("entropy"));
    }

    @Test
    void calculateEntropyEmpty() {
        assertEquals(0, DensityGate.calculateEntropy(null));
        assertEquals(0, DensityGate.calculateEntropy(""));
    }

    @Test
    void calculateEntropyNonEmpty() {
        double e = DensityGate.calculateEntropy("abc");
        assertTrue(e > 0);
    }

    @Test
    void calculateLdrInvalidJava() {
        assertEquals(0, DensityGate.calculateLdr("not valid {"));
    }

    @Test
    void passesWhenRefactoringNetNegativeLoc() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.MODIFIED, "class Foo {}")),
                "", 10, 100, null, false);
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("refactor_net_loc_threshold", -50))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    @Test
    void passesWhenTrustedAuthor() {
        var gate = new DensityGate();
        var diff = new Diff("main", "HEAD",
                List.of(new ChangedFile("Foo.java", ChangedFile.ChangeType.ADDED, "aaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                "", 1, 0, "committer@apache.org", false);
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("trusted_authors", List.of("committer@apache.org")))),
                Map.of());
        var ctx = new AIVContext(Paths.get("."), diff, config);
        assertTrue(gate.evaluate(ctx).isPassed());
    }

    private AIVContext context(List<ChangedFile> files) {
        var diff = new Diff("main", "HEAD", files, "");
        var config = new AIVConfig(
                List.of(new AIVConfig.GateConfig("density", true, Map.of("ldr_threshold", 0.25, "entropy_threshold", 3.8))),
                Map.of()
        );
        return new AIVContext(Paths.get("."), diff, config);
    }
}
