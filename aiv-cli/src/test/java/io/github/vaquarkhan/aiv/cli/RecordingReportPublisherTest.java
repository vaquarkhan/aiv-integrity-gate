/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.ReportPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class RecordingReportPublisherTest {

    @Test
    void delegatesAndKeepsLastResult() {
        List<AIVResult> seen = new ArrayList<>();
        ReportPublisher delegate = seen::add;
        var recording = new RecordingReportPublisher(delegate);
        var result = new AIVResult(true, List.of(GateResult.pass("density")), List.of());
        recording.publish(result);
        assertSame(result, recording.getLastResult());
        assertSame(result, seen.get(0));
    }
}
