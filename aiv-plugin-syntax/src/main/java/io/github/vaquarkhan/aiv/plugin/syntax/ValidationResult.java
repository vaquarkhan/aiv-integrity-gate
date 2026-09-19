/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

/**
 * Outcome of a single-file parse check. {@code ok=true} means the file parsed (or the check was
 * skipped because no validator was available); a failure carries an optional 1-based line and detail.
 */
public record ValidationResult(boolean valid, Integer line, String detail) {

    public static ValidationResult ok() {
        return new ValidationResult(true, null, null);
    }

    public static ValidationResult fail(Integer line, String detail) {
        String message = detail;
        if (message == null || message.isBlank()) {
            message = "unparseable";
        }
        Integer safeLine = line;
        if (safeLine == null || safeLine < 1) {
            safeLine = 1;
        }
        return new ValidationResult(false, safeLine, message);
    }
}
