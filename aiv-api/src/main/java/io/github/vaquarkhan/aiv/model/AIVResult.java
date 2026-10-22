/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import java.util.Collections;
import java.util.List;

/**
 * Aggregated result from all gates. Overall pass/fail and individual gate results.
 *
 * @author Vaquar Khan
 */
public final class AIVResult {

    private final boolean passed;
    private final List<GateResult> gateResults;
    private final List<String> notices;

    public AIVResult(boolean passed, List<GateResult> gateResults) {
        this(passed, gateResults, List.of());
    }

    public AIVResult(boolean passed, List<GateResult> gateResults, List<String> notices) {
        this.passed = passed;
        this.gateResults = gateResults == null ? Collections.emptyList() : List.copyOf(gateResults);
        this.notices = notices == null ? List.of() : List.copyOf(notices);
    }

    public boolean isPassed() {
        return passed;
    }

    public List<GateResult> getGateResults() {
        return gateResults;
    }

    public List<String> getNotices() {
        return notices;
    }

    /**
     * True when any gate reported a non-blocking failure ({@code severity: warn} / advisory).
     */
    public boolean hasAdvisoryFailures() {
        for (GateResult g : gateResults) {
            if (!g.isPassed() && !g.blocksCi()) {
                return true;
            }
        }
        return false;
    }
}
