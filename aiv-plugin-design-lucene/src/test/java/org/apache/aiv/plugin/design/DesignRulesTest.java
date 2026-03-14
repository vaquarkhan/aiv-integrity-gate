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

package org.apache.aiv.plugin.design;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class DesignRulesTest {

    @Test
    void constraintGetters() {
        var c = new DesignRules.Constraint("id", List.of("k"), List.of("f"), List.of("r"));
        assertEquals("id", c.getId());
        assertEquals(1, c.getKeywords().size());
        assertEquals(1, c.getForbiddenCalls().size());
        assertEquals(1, c.getRequiredCalls().size());
    }

    @Test
    void nullListsBecomeEmpty() {
        var c = new DesignRules.Constraint("x", null, null, null);
        assertTrue(c.getKeywords().isEmpty());
        assertTrue(c.getForbiddenCalls().isEmpty());
        assertTrue(c.getRequiredCalls().isEmpty());
    }

    @Test
    void rulesGetters() {
        var c = new DesignRules.Constraint("x", List.of(), List.of(), List.of());
        var r = new DesignRules(List.of(c));
        assertEquals(1, r.getConstraints().size());
    }

    @Test
    void nullConstraintsBecomeEmpty() {
        var r = new DesignRules(null);
        assertNotNull(r.getConstraints());
        assertTrue(r.getConstraints().isEmpty());
    }
}
