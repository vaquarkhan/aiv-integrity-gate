/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.port.ReportPublisher;

/**
 * Delegates to another publisher and keeps the last {@link AIVResult} for JSON export.
 */
public final class RecordingReportPublisher implements ReportPublisher {

    private final ReportPublisher delegate;
    private AIVResult lastResult;

    public RecordingReportPublisher(ReportPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(AIVResult result) {
        this.lastResult = result;
        delegate.publish(result);
    }

    public AIVResult getLastResult() {
        return lastResult;
    }
}
