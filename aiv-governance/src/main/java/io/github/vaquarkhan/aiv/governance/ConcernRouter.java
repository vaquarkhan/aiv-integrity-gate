/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.governance;

/**
 * The orchestration policy in one place: for a concern, prefer an existing tool, fall back to an
 * AIV built-in gate, otherwise skip. This is the "do not replace, orchestrate" rule expressed as a
 * pure function so the decision itself is deterministic and auditable.
 *
 * @author Vaquar Khan
 */
public final class ConcernRouter {

    private ConcernRouter() {
    }

    /**
     * @param hasDelegate true when the project already runs a tool for this concern
     * @param hasBuiltin  true when AIV has a built-in gate for this concern
     * @return the tier that should handle the concern
     */
    public static ConcernOutcome.Mode resolveMode(boolean hasDelegate, boolean hasBuiltin) {
        if (hasDelegate) {
            return ConcernOutcome.Mode.DELEGATED;
        }
        if (hasBuiltin) {
            return ConcernOutcome.Mode.BUILTIN;
        }
        return ConcernOutcome.Mode.SKIPPED;
    }
}
