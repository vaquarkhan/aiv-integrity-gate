/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

/**
 * A file that was added or modified in the diff.
 *
 * @author Vaquar Khan
 */
public final class ChangedFile {

    private final String path;
    private final ChangeType changeType;
    private final String content;

    public enum ChangeType {
        ADDED,
        MODIFIED
    }

    public ChangedFile(String path, ChangeType changeType, String content) {
        this.path = path;
        this.changeType = changeType;
        this.content = content != null ? content : "";
    }

    public String getPath() {
        return path;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getContent() {
        return content;
    }
}
