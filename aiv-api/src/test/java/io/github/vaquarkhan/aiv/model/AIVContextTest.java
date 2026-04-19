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

import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class AIVContextTest {

    @Test
    void gettersReturnValues() {
        var workspace = Paths.get("/tmp");
        var diff = new Diff("main", "HEAD", java.util.List.of(), "diff");
        var config = new AIVConfig(java.util.List.of(), java.util.Map.of());
        var ctx = new AIVContext(workspace, diff, config);
        assertEquals(workspace, ctx.getWorkspace());
        assertEquals(diff, ctx.getDiff());
        assertEquals(config, ctx.getConfig());
        assertTrue(ctx.getMetadata().isEmpty());
    }

    @Test
    void withMetadataCreatesNewContext() {
        var ctx = new AIVContext(Paths.get("/"), new Diff("a", "b", java.util.List.of(), ""), new AIVConfig(java.util.List.of(), java.util.Map.of()));
        var withMeta = ctx.withMetadata("key", "value");
        assertEquals("value", withMeta.getMetadata().get("key"));
        assertTrue(ctx.getMetadata().isEmpty());
    }

    @Test
    void metadataNullBecomesEmpty() {
        var ctx = new AIVContext(Paths.get("/"), new Diff("a", "b", java.util.List.of(), ""), new AIVConfig(java.util.List.of(), java.util.Map.of()), null);
        assertNotNull(ctx.getMetadata());
        assertTrue(ctx.getMetadata().isEmpty());
    }
}
