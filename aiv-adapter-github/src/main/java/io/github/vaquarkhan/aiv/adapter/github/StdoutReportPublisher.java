/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.github;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.ReportPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes AIV results to {@link System#out} for reliable CI capture; logs a one-line summary at INFO.
 *
 * @author Vaquar Khan
 */
public final class StdoutReportPublisher implements ReportPublisher {

    private static final Logger log = LoggerFactory.getLogger(StdoutReportPublisher.class);

    private final boolean quiet;

    /** Human-readable report to stdout plus one-line INFO summary (default). */
    public StdoutReportPublisher() {
        this(false);
    }

    /**
     * @param quiet when true, suppresses the multi-line report and the INFO summary (use with
     *              {@code --output-json} / {@code --output-sarif} for machine-only output).
     */
    public StdoutReportPublisher(boolean quiet) {
        this.quiet = quiet;
    }

    @Override
    public void publish(AIVResult result) {
        if (quiet) {
            log.debug("AIV {} (quiet: stdout report suppressed)", result.isPassed() ? "PASS" : "FAIL");
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean doctor = result.getNotices().stream().anyMatch(n -> n != null && n.startsWith("[DOCTOR]"));
        sb.append(doctor ? "=== AIV Report (DOCTOR) ===\n" : "=== AIV Report ===\n");
        sb.append("Overall: ").append(result.isPassed() ? "PASS" : "FAIL").append("\n");
        for (String n : result.getNotices()) {
            sb.append("WARN: ").append(n).append("\n");
        }
        for (GateResult gr : result.getGateResults()) {
            sb.append("  ").append(gr.getGateId()).append(": ");
            if (gr.isPassed()) {
                sb.append("PASS");
            } else {
                sb.append(gr.blocksCi() ? "FAIL" : "ADVISORY");
            }
            if (!gr.getMessage().isEmpty()) {
                sb.append(" - ").append(gr.getMessage());
            }
            sb.append("\n");
        }
        sb.append("==================\n");
        System.out.print(sb.toString());
        log.info("AIV {}", result.isPassed() ? "PASS" : "FAIL");
    }
}
