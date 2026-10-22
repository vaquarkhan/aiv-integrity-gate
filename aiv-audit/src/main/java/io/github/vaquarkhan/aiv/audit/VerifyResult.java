/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.audit;

/**
 * Outcome of verifying a hash-chained audit log.
 *
 * @author Vaquar Khan
 */
public final class VerifyResult {

    private final boolean intact;
    private final int firstBrokenIndex;
    private final int recordCount;

    private VerifyResult(boolean intact, int firstBrokenIndex, int recordCount) {
        this.intact = intact;
        this.firstBrokenIndex = firstBrokenIndex;
        this.recordCount = recordCount;
    }

    /** The chain verifies end to end. */
    public static VerifyResult intact(int recordCount) {
        return new VerifyResult(true, -1, recordCount);
    }

    /** The chain is broken at {@code index} (0-based line of the first tampered/broken record). */
    public static VerifyResult brokenAt(int index, int recordCount) {
        return new VerifyResult(false, index, recordCount);
    }

    /**
     * The chain is internally consistent but its head does not match the externally anchored value.
     * This is the case that catches a writer who rewrote records and recomputed the whole chain.
     */
    public static VerifyResult headMismatch(int recordCount) {
        return new VerifyResult(false, -2, recordCount);
    }

    /** True when the failure is specifically an anchored-head mismatch (see {@link #headMismatch}). */
    public boolean isHeadMismatch() {
        return firstBrokenIndex == -2;
    }

    public boolean isIntact() {
        return intact;
    }

    /** 0-based index of the first broken record, or -1 when intact. */
    public int getFirstBrokenIndex() {
        return firstBrokenIndex;
    }

    public int getRecordCount() {
        return recordCount;
    }
}
