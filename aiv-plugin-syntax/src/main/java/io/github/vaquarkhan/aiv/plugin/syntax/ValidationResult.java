/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

/** Outcome of a single-file parse check. A valid result with detail beginning SKIPPED is unverified. */
public record ValidationResult(boolean valid, Integer line, String detail) {

    public static ValidationResult ok() {
        return new ValidationResult(true, null, null);
    }

    public static ValidationResult skipped(String detail) {
        String reason = detail == null || detail.isBlank() ? "validator unavailable" : detail;
        return new ValidationResult(true, null, "SKIPPED: " + reason);
    }

    public boolean skipped() {
        return valid && detail != null && detail.startsWith("SKIPPED:");
    }

    public static ValidationResult fail(Integer line, String detail) {
        String message = detail;
        if (message == null || message.isBlank()) message = "unparseable";
        Integer safeLine = line;
        if (safeLine == null || safeLine < 1) safeLine = 1;
        return new ValidationResult(false, safeLine, message);
    }
}
