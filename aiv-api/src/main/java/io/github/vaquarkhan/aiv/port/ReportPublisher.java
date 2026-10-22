/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.port;

import io.github.vaquarkhan.aiv.model.AIVResult;

/**
 * Publishes AIV result to CI (e.g. GitHub comment, JUnit XML, stdout).
 *
 * @author Vaquar Khan
 */
public interface ReportPublisher {

    void publish(AIVResult result);
}
