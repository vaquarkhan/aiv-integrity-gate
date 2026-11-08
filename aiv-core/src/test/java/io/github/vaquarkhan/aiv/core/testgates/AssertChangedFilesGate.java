/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.core.testgates;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

public final class AssertChangedFilesGate implements QualityGate {

    @Override
    public String getId() {
        return "assert-files";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Object expected = context.getConfig().getGlobalConfig().get("expected_changed_files");
        int expectedCount = coerceInt(expected, -1);
        int actualCount = context.getDiff().getChangedFiles().size();
        boolean passed = expectedCount >= 0 && expectedCount == actualCount;
        Object expectedExcluded = context.getConfig().getGlobalConfig().get("expected_excluded_paths");
        Object excluded = context.getMetadata().get(AIVContext.EXCLUDED_CHANGED_FILES);
        String excludedPaths = excluded instanceof java.util.List<?> list
                ? list.stream().map(o -> ((ChangedFile) o).getPath()).toList().toString() : "none";
        if (expectedExcluded != null) {
            passed = passed && expectedExcluded.toString().equals(excludedPaths);
        }
        return new GateResult(getId(), passed, "expected=" + expectedCount + " actual=" + actualCount
                + " excluded=" + excludedPaths);
    }

    private static int coerceInt(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}

