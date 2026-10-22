/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Vaquar Khan
 */
class HashChainAuditTest {

    @Test
    void appendCreatesFileAndChainsFromGenesis(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("nested/audit.log");
        String h1 = HashChainAudit.append(log, "{\"verdict\":\"approve\"}");
        assertTrue(Files.exists(log));
        assertEquals(HashChainAudit.sha256Hex(HashChainAudit.GENESIS
                + java.util.Base64.getEncoder().encodeToString(
                        "{\"verdict\":\"approve\"}".getBytes(StandardCharsets.UTF_8))), h1);
        assertEquals(h1, HashChainAudit.headHash(log));
    }

    @Test
    void multipleAppendsVerifyIntact(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        String h1 = HashChainAudit.append(log, "r1");
        HashChainAudit.append(log, "r2");
        VerifyResult r = HashChainAudit.verify(log);
        assertTrue(r.isIntact());
        assertEquals(-1, r.getFirstBrokenIndex());
        assertEquals(3, r.getRecordCount());
        assertNotEquals(HashChainAudit.GENESIS, h1);
    }

    @Test
    void detectsRetroactiveEdit(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        HashChainAudit.append(log, "r1");
        HashChainAudit.append(log, "r2");
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        String[] parts = lines.get(1).split("\t", -1);
        // Rewrite the payload of record 1 without fixing the hash chain.
        parts[2] = java.util.Base64.getEncoder().encodeToString("tampered".getBytes(StandardCharsets.UTF_8));
        lines.set(1, parts[0] + "\t" + parts[1] + "\t" + parts[2]);
        Files.write(log, lines, StandardCharsets.UTF_8);
        VerifyResult r = HashChainAudit.verify(log);
        assertFalse(r.isIntact());
        assertEquals(1, r.getFirstBrokenIndex());
    }

    @Test
    void detectsDeletedRecord(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        HashChainAudit.append(log, "r1");
        HashChainAudit.append(log, "r2");
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        lines.remove(1); // delete middle record -> record 1 (old r2) prev no longer links
        Files.write(log, lines, StandardCharsets.UTF_8);
        VerifyResult r = HashChainAudit.verify(log);
        assertFalse(r.isIntact());
        assertEquals(1, r.getFirstBrokenIndex());
    }

    @Test
    void detectsMalformedLine(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        Files.writeString(log, "not-a-valid-record" + System.lineSeparator(),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        VerifyResult r = HashChainAudit.verify(log);
        assertFalse(r.isIntact());
        assertEquals(1, r.getFirstBrokenIndex());
    }

    @Test
    void verifyMissingFileIsIntactEmpty(@TempDir Path dir) throws IOException {
        VerifyResult r = HashChainAudit.verify(dir.resolve("does-not-exist.log"));
        assertTrue(r.isIntact());
        assertEquals(0, r.getRecordCount());
    }

    @Test
    void headHashMissingFileIsGenesis(@TempDir Path dir) throws IOException {
        assertEquals(HashChainAudit.GENESIS, HashChainAudit.headHash(dir.resolve("none.log")));
    }

    @Test
    void headHashBlankFileIsGenesis(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("blank.log");
        Files.writeString(log, System.lineSeparator() + "   " + System.lineSeparator(), StandardCharsets.UTF_8);
        assertEquals(HashChainAudit.GENESIS, HashChainAudit.headHash(log));
    }

    @Test
    void headHashMalformedLastLineIsGenesis(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("bad.log");
        Files.writeString(log, "garbage-line" + System.lineSeparator(), StandardCharsets.UTF_8);
        assertEquals(HashChainAudit.GENESIS, HashChainAudit.headHash(log));
    }

    @Test
    void digestHexRejectsUnknownAlgorithm() {
        assertThrows(IllegalStateException.class, () -> HashChainAudit.digestHex("NO-SUCH-ALG", "x"));
    }

    @Test
    void anchoredVerifyMatchesHead(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        String head = HashChainAudit.append(log, "r1");
        VerifyResult r = HashChainAudit.verify(log, head);
        assertTrue(r.isIntact());
        assertFalse(r.isHeadMismatch());
    }

    @Test
    void anchoredVerifyDetectsHeadMismatch(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        HashChainAudit.append(log, "r1");
        VerifyResult r = HashChainAudit.verify(log, "deadbeef");
        assertFalse(r.isIntact());
        assertTrue(r.isHeadMismatch());
        assertEquals(2, r.getRecordCount());
    }

    @Test
    void anchoredVerifyNullAnchorSkipsHeadCheck(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        VerifyResult r = HashChainAudit.verify(log, null);
        assertTrue(r.isIntact());
    }

    @Test
    void anchoredVerifyShortCircuitsOnBrokenChain(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("audit.log");
        HashChainAudit.append(log, "r0");
        HashChainAudit.append(log, "r1");
        HashChainAudit.append(log, "r2");
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        String[] parts = lines.get(1).split("\t", -1);
        parts[2] = java.util.Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        lines.set(1, parts[0] + "\t" + parts[1] + "\t" + parts[2]);
        Files.write(log, lines, StandardCharsets.UTF_8);
        VerifyResult r = HashChainAudit.verify(log, "anything");
        assertFalse(r.isIntact());
        assertFalse(r.isHeadMismatch());
        assertEquals(1, r.getFirstBrokenIndex());
    }

    @Test
    void concurrentAppendsRemainOneIntactChain(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("concurrent.log");
        int count = 24;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<String>> writes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i;
            writes.add(pool.submit(() -> {
                start.await();
                return HashChainAudit.append(log, "record-" + n);
            }));
        }
        start.countDown();
        for (var write : writes) {
            assertTrue(write.get(10, TimeUnit.SECONDS).length() == 64);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        VerifyResult verified = HashChainAudit.verify(log);
        assertTrue(verified.isIntact());
        assertEquals(count, verified.getRecordCount());
    }

    @Test
    void separateProcessesAppendWithoutForkingChain(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("processes.log");
        List<Process> writers = new ArrayList<>();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        for (int i = 0; i < 8; i++) {
            writers.add(new ProcessBuilder(java, "-cp", classpath,
                    ProcessWriter.class.getName(), log.toString(), "p-" + i).start());
        }
        for (Process writer : writers) {
            assertTrue(writer.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, writer.exitValue());
        }
        VerifyResult verified = HashChainAudit.verify(log);
        assertTrue(verified.isIntact());
        assertEquals(8, verified.getRecordCount());
    }

    /** Child-process entry point used to prove the OS file lock, not only the in-JVM monitor. */
    public static final class ProcessWriter {
        private ProcessWriter() {
        }

        public static void main(String[] args) throws Exception {
            HashChainAudit.append(Path.of(args[0]), args[1]);
        }
    }

    @Test
    void privateConstructorIsNotUsable() throws Exception {
        Constructor<HashChainAudit> c = HashChainAudit.class.getDeclaredConstructor();
        c.setAccessible(true);
        c.newInstance();
    }
}
