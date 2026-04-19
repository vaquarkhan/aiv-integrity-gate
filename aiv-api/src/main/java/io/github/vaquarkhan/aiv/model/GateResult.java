/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import java.util.Collections;
import java.util.List;

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
    private final List<Finding> findings;

    public GateResult(String gateId, boolean passed, String message) {
        this(gateId, passed, message, !passed, List.of());
    }

    public GateResult(String gateId, boolean passed, String message, boolean blocksCi) {
        this(gateId, passed, message, blocksCi, List.of());
    }

    public GateResult(String gateId, boolean passed, String message, boolean blocksCi, List<Finding> findings) {
        this.gateId = gateId;
        this.passed = passed;
        this.message = message != null ? message : "";
        this.blocksCi = passed ? false : blocksCi;
        this.findings = findings == null || findings.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(findings));
    }

    public static GateResult pass(String gateId) {
        return new GateResult(gateId, true, "OK", false, List.of());
    }

    public static GateResult fail(String gateId, String message) {
        return new GateResult(gateId, false, message, true, List.of());
    }

    public static GateResult fail(String gateId, String message, List<Finding> findings) {
        return new GateResult(gateId, false, message, true, findings);
    }

    /** Failed gate that does not fail CI (warn-only / report-only). */
    public static GateResult advisory(String gateId, String message) {
        return new GateResult(gateId, false, message, false, List.of());
    }

    public static GateResult advisory(String gateId, String message, List<Finding> findings) {
        return new GateResult(gateId, false, message, false, findings);
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

    /**
     * Structured locations for SARIF, JSON, and GitHub Checks; empty when not supplied by the gate.
     */
    public List<Finding> getFindings() {
        return findings;
    }
}
