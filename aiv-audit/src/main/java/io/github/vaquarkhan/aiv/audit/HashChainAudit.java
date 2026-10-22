/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.audit;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * Append-only, tamper-evident audit log using a SHA-256 hash chain.
 *
 * <p>Each record commits to the entire prior history: {@code h_i = SHA256(h_(i-1) || payload_i)}.
 * Any retroactive edit, deletion, or reordering breaks the chain and is caught by {@link #verify},
 * which reports the first broken index. The log is a plain UTF-8 text file with one record per line
 * in the form {@code prevHash \t hash \t base64(payload)}; the payload is base64-encoded so it can
 * carry arbitrary verdict/override content without disturbing the line format.
 *
 * <p>This is deliberately dependency-free and deterministic: the same payloads always produce the
 * same chain, so a verdict trail is byte-reproducible and can serve as a compliance evidence record.
 *
 * @author Vaquar Khan
 */
public final class HashChainAudit {

    /** Genesis hash for the first record (64 zero hex chars). */
    public static final String GENESIS = "0".repeat(64);

    private static final char SEP = '\t';

    private HashChainAudit() {
    }

    /**
     * Append {@code payload} to the chained log at {@code logFile}, creating the file and parent
     * directories if needed. Returns the new head hash.
     */
    public static synchronized String append(Path logFile, String payload) throws IOException {
        Path absolute = logFile.toAbsolutePath().normalize();
        Path dir = absolute.getParent();
        Files.createDirectories(dir);
        Path lockPath = dir.resolve(absolute.getFileName().toString() + ".lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {
            String prev = headHash(absolute);
            String payloadB64 = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String hash = sha256Hex(prev + payloadB64);
            String line = prev + SEP + hash + SEP + payloadB64 + System.lineSeparator();
            Files.writeString(absolute, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return hash;
        }
    }

    /** The current head hash: the last record's hash, or {@link #GENESIS} for an empty/new log. */
    public static String headHash(Path logFile) throws IOException {
        if (!Files.exists(logFile)) {
            return GENESIS;
        }
        List<String> lines = nonBlankLines(logFile);
        if (lines.isEmpty()) {
            return GENESIS;
        }
        String[] parts = lines.get(lines.size() - 1).split("\t", -1);
        if (parts.length != 3) {
            return GENESIS;
        }
        return parts[1];
    }

    /**
     * Recompute the chain and report whether it is intact. On a break, the returned index is the
     * 0-based line of the first record that does not verify.
     */
    public static VerifyResult verify(Path logFile) throws IOException {
        if (!Files.exists(logFile)) {
            return VerifyResult.intact(0);
        }
        List<String> lines = nonBlankLines(logFile);
        String prev = GENESIS;
        for (int i = 0; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            if (parts.length != 3) {
                return VerifyResult.brokenAt(i, lines.size());
            }
            String prevField = parts[0];
            String hashField = parts[1];
            String payloadB64 = parts[2];
            if (!prevField.equals(prev)) {
                return VerifyResult.brokenAt(i, lines.size());
            }
            String recomputed = sha256Hex(prev + payloadB64);
            if (!recomputed.equals(hashField)) {
                return VerifyResult.brokenAt(i, lines.size());
            }
            prev = hashField;
        }
        return VerifyResult.intact(lines.size());
    }

    /**
     * Verify the chain AND that its head equals {@code expectedHead}, an anchor stored outside the
     * log (a CI job log, a signed tag, or a transparency log). This is what makes the log genuinely
     * tamper-evident: a writer who rewrites records and recomputes the whole chain still cannot make
     * the recomputed head match the externally anchored value. A {@code null} anchor skips the head
     * check and behaves like {@link #verify(Path)}.
     */
    public static VerifyResult verify(Path logFile, String expectedHead) throws IOException {
        VerifyResult base = verify(logFile);
        if (!base.isIntact()) {
            return base;
        }
        if (expectedHead == null) {
            return base;
        }
        if (!expectedHead.equals(headHash(logFile))) {
            return VerifyResult.headMismatch(base.getRecordCount());
        }
        return base;
    }

    /**
     * Decoded payloads in chain order. Callers that need the guarantee must {@link #verify} first;
     * this method only decodes, so a malformed line is reported as an {@link IllegalStateException}.
     */
    public static List<String> payloads(Path logFile) throws IOException {
        List<String> out = new java.util.ArrayList<>();
        if (!Files.exists(logFile)) {
            return out;
        }
        List<String> lines = nonBlankLines(logFile);
        for (int i = 0; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            if (parts.length != 3) {
                throw new IllegalStateException("audit line " + i + " is not prev<TAB>hash<TAB>payload");
            }
            try {
                out.add(new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("audit line " + i + " payload is not base64", e);
            }
        }
        return out;
    }

    private static List<String> nonBlankLines(Path logFile) throws IOException {
        List<String> all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        all.removeIf(String::isBlank);
        return all;
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code input}; shared so every module hashes the same way. */
    public static String sha256Hex(String input) {
        return digestHex("SHA-256", input);
    }

    static String digestHex(String algorithm, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
