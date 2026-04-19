/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

/**
 * Result from a single quality gate evaluation.
 *
 * @author Vaquar Khan
 */
public final class GateResult {

    private final String gateId;
    private final boolean passed;
    private final String message;
    /** When {@code !passed}, if {@code false} the failure is advisory only (does not fail CI). */
    private final boolean blocksCi;

    public GateResult(String gateId, boolean passed, String message) {
        this(gateId, passed, message, !passed);
    }

    public GateResult(String gateId, boolean passed, String message, boolean blocksCi) {
        this.gateId = gateId;
        this.passed = passed;
        this.message = message != null ? message : "";
        this.blocksCi = passed ? false : blocksCi;
    }

    public static GateResult pass(String gateId) {
        return new GateResult(gateId, true, "OK", false);
    }

    public static GateResult fail(String gateId, String message) {
        return new GateResult(gateId, false, message, true);
    }

    /** Failed gate that does not fail CI (warn-only / report-only). */
    public static GateResult advisory(String gateId, String message) {
        return new GateResult(gateId, false, message, false);
    }

    public String getGateId() {
        return gateId;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getMessage() {
        return message;
    }

    /**
     * When the gate did not pass, returns whether this outcome should fail the run (exit non-zero).
     */
    public boolean blocksCi() {
        return blocksCi;
    }
}
