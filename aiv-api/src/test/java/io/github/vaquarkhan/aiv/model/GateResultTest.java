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

package io.github.vaquarkhan.aiv.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class GateResultTest {

    @Test
    void passFactory() {
        var r = GateResult.pass("density");
        assertTrue(r.isPassed());
        assertEquals("density", r.getGateId());
        assertEquals("OK", r.getMessage());
    }

    @Test
    void failFactory() {
        var r = GateResult.fail("design", "forbidden call");
        assertFalse(r.isPassed());
        assertEquals("design", r.getGateId());
        assertEquals("forbidden call", r.getMessage());
    }

    @Test
    void nullMessageBecomesEmpty() {
        var r = new GateResult("x", false, null);
        assertEquals("", r.getMessage());
        assertTrue(r.blocksCi());
    }

    @Test
    void advisoryDoesNotBlockCi() {
        var r = GateResult.advisory("g", "msg");
        assertFalse(r.isPassed());
        assertFalse(r.blocksCi());
    }
}
