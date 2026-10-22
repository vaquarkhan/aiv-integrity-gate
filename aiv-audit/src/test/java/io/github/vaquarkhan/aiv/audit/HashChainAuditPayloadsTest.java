/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashChainAuditPayloadsTest {

    @Test
    void decodesPayloadsInOrder(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("a.log");
        assertTrue(HashChainAudit.payloads(log).isEmpty());
        HashChainAudit.append(log, "{\"n\":1}");
        HashChainAudit.append(log, "line\nwith\ttabs and ünïcode");
        assertEquals(List.of("{\"n\":1}", "line\nwith\ttabs and ünïcode"), HashChainAudit.payloads(log));
    }

    @Test
    void malformedLinesAreReported(@TempDir Path dir) throws IOException {
        Path bad = dir.resolve("bad.log");
        Files.writeString(bad, "only-one-field\n", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> HashChainAudit.payloads(bad));
        Files.writeString(bad, "a\tb\t***not base64***\n", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> HashChainAudit.payloads(bad));
    }
}
