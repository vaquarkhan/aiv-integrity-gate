/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
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
        assertEquals(workspace, ctx.getPolicyWorkspace());
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
    void separatePolicyWorkspaceIsPreservedWithMetadata() {
        var workspace = Paths.get("/work");
        var policy = Paths.get("/policy");
        var context = new AIVContext(workspace, policy, new Diff("a", "b", java.util.List.of(), ""),
                new AIVConfig(java.util.List.of(), java.util.Map.of()), Map.of());
        assertEquals(policy, context.getPolicyWorkspace());
        assertEquals(policy, context.withMetadata("k", "v").getPolicyWorkspace());
        var fallback = new AIVContext(workspace, null, context.getDiff(), context.getConfig(), null);
        assertEquals(workspace, fallback.getPolicyWorkspace());
    }

    @Test
    void metadataNullBecomesEmpty() {
        var ctx = new AIVContext(Paths.get("/"), new Diff("a", "b", java.util.List.of(), ""), new AIVConfig(java.util.List.of(), java.util.Map.of()), null);
        assertNotNull(ctx.getMetadata());
        assertTrue(ctx.getMetadata().isEmpty());
    }
}
