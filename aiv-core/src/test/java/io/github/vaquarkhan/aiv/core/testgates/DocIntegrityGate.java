/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core.testgates;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

/** Test double for the real doc-integrity gate id and SPI loading. */
public final class DocIntegrityGate implements QualityGate {

    @Override
    public String getId() {
        return "doc-integrity";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        return new GateResult(getId(), true, "OK");
    }
}
