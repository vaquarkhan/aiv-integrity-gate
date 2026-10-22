/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core.testgates;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

public final class FailGate implements QualityGate {

    @Override
    public String getId() {
        return "fail";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        return io.github.vaquarkhan.aiv.model.GateResult.fail(getId(), "FAIL",
                java.util.List.of(io.github.vaquarkhan.aiv.model.Finding.atLine("fail.rule", "a.txt", 1, "FAIL")));
    }
}

