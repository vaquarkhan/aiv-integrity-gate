/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.governance;

import java.util.Objects;

/**
 * The result of one governance concern, whatever produced it: a delegated external tool, an AIV
 * built-in fallback gate, or a skip. This is the normalized unit the report and audit are built
 * from, so the record looks the same whether gitleaks, RAT, Magpie, or AIV itself did the work.
 *
 * @author Vaquar Khan
 */
public final class ConcernOutcome {

    /** Which tier produced this outcome. */
    public enum Mode {
        /** An existing tool ran; AIV only orchestrated and recorded it. */
        DELEGATED,
        /** No incumbent existed; AIV's own gate produced it. */
        BUILTIN,
        /** The concern did not apply. */
        SKIPPED
    }

    /** Normalized status across all providers. */
    public enum Status {
        PASS,
        WARN,
        FAIL
    }

    private final String concern;
    private final String provider;
    private final Mode mode;
    private final Status status;
    private final String detail;

    public ConcernOutcome(String concern, String provider, Mode mode, Status status, String detail) {
        this.concern = Objects.requireNonNull(concern, "concern");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.status = Objects.requireNonNull(status, "status");
        this.detail = detail == null ? "" : detail;
    }

    public String getConcern() {
        return concern;
    }

    public String getProvider() {
        return provider;
    }

    public Mode getMode() {
        return mode;
    }

    public Status getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }
}
