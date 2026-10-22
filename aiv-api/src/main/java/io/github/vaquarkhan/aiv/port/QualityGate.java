/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.port;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.GateResult;

/**
 * SPI for quality gates. Each plugin implements this and is discovered via ServiceLoader.
 *
 * @author Vaquar Khan
 */
public interface QualityGate {

    /**
     * Unique id for this gate, e.g. "density", "design", "invariant".
     */
    String getId();

    /**
     * Evaluate the context and return pass/fail.
     */
    GateResult evaluate(AIVContext context);
}
