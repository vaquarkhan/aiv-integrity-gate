/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.pkgexist;

import java.util.Optional;

/**
 * Answers "does this package exist in its public registry?".
 * {@code Optional.empty()} means the registry could not be consulted (offline, timeout, 5xx):
 * the caller must treat that as unknown and report it, never as pass or fail.
 */
@FunctionalInterface
public interface RegistryClient {

    /** {@code ecosystem} is one of {@code pypi}, {@code npm}, {@code maven}. */
    Optional<Boolean> exists(String ecosystem, String name);
}
