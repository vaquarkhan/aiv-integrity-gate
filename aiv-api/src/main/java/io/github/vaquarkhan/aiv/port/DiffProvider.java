/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.port;

import io.github.vaquarkhan.aiv.model.Diff;

import java.nio.file.Path;

/**
 * Provides git diff between base and head refs.
 *
 * @author Vaquar Khan
 */
public interface DiffProvider {

    Diff getDiff(Path workspace, String baseRef, String headRef);
}
