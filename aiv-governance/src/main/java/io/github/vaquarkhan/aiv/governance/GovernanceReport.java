/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.governance;

import java.util.List;

/**
 * Renders a unified, deterministic governance report across every concern, regardless of which tool
 * produced each outcome. This is the single artifact a maintainer or auditor reads instead of N
 * separate tool outputs, and the same text is what gets committed to the tamper-evident audit.
 *
 * @author Vaquar Khan
 */
public final class GovernanceReport {

    private GovernanceReport() {
    }

    /** FAIL if any concern failed; else WARN if any warned; else PASS (also for an empty set). */
    public static ConcernOutcome.Status overall(List<ConcernOutcome> outcomes) {
        boolean warn = false;
        for (ConcernOutcome o : outcomes) {
            if (o.getStatus() == ConcernOutcome.Status.FAIL) {
                return ConcernOutcome.Status.FAIL;
            }
            if (o.getStatus() == ConcernOutcome.Status.WARN) {
                warn = true;
            }
        }
        return warn ? ConcernOutcome.Status.WARN : ConcernOutcome.Status.PASS;
    }

    /** A human-readable Markdown report. */
    public static String render(List<ConcernOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AIV Governance Report\n\n");
        sb.append("Overall: ").append(overall(outcomes)).append("\n\n");
        sb.append("| Concern | Provider | Mode | Status | Detail |\n");
        sb.append("|---|---|---|---|---|\n");
        for (ConcernOutcome o : outcomes) {
            sb.append("| ").append(o.getConcern())
                    .append(" | ").append(o.getProvider())
                    .append(" | ").append(o.getMode())
                    .append(" | ").append(o.getStatus())
                    .append(" | ").append(o.getDetail())
                    .append(" |\n");
        }
        return sb.toString();
    }

    /** A compact, deterministic one-line-per-concern payload for the audit record. */
    public static String canonical(List<ConcernOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("overall=").append(overall(outcomes));
        for (ConcernOutcome o : outcomes) {
            sb.append('\n').append(o.getConcern())
                    .append('|').append(o.getProvider())
                    .append('|').append(o.getMode())
                    .append('|').append(o.getStatus());
        }
        return sb.toString();
    }
}
