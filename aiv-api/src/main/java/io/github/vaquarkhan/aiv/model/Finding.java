/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

import java.util.Objects;

/**
 * A single structured issue for SARIF, JSON reports, and GitHub Checks annotations.
 * Lines and columns are 1-based as in editors; optional fields may be null.
 */
public final class Finding {

    private final String ruleId;
    private final String filePath;
    private final int startLine;
    private final Integer startColumn;
    private final Integer endLine;
    private final Integer endColumn;
    private final String message;

    public Finding(String ruleId, String filePath, int startLine, Integer startColumn,
                   Integer endLine, Integer endColumn, String message) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.message = message != null ? message : "";
    }

    /** Convenience: one line, no columns. */
    public static Finding atLine(String ruleId, String filePath, int startLine, String message) {
        return new Finding(ruleId, filePath, startLine, null, null, null, message);
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getStartLine() {
        return startLine;
    }

    public Integer getStartColumn() {
        return startColumn;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public Integer getEndColumn() {
        return endColumn;
    }

    public String getMessage() {
        return message;
    }
}
