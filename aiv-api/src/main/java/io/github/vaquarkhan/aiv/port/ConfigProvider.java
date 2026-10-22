/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.port;

import io.github.vaquarkhan.aiv.model.AIVConfig;

import java.nio.file.Path;

/**
 * Loads AIV config from workspace (e.g. .aiv/config.yaml).
 *
 * @author Vaquar Khan
 */
public interface ConfigProvider {

    AIVConfig getConfig(Path workspace);
}
