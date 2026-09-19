/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

/**
 * Validates that a single file's source text parses. Injectable so language checks that need an
 * external toolchain (e.g. a Python interpreter) can be faked in tests.
 */
public interface SourceValidator {

    ValidationResult validate(String source);
}
