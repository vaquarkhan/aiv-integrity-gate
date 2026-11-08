/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.model;

/**
 * One path-level change with optional immutable Git structure evidence.
 *
 * <p>The three-argument constructor remains source-compatible and marks structure metadata as
 * caller asserted. Only metadata produced by the trusted Git adapter is authoritative for hard
 * repository-structure findings such as an unexpected gitlink.
 *
 * @author Vaquar Khan
 */
public final class ChangedFile {

    public enum ChangeType {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED,
        COPIED
    }

    public enum ObjectType {
        BLOB,
        GITLINK,
        UNKNOWN
    }

    public enum SourceCompleteness {
        COMPLETE,
        FRAGMENT,
        TRUNCATED
    }

    public enum Provenance {
        TRUSTED_GIT_ADAPTER,
        SIGNED_HOST,
        CALLER_ASSERTED
    }

    private final String path;
    private final ChangeType changeType;
    private final String content;
    private final String oldPath;
    private final String oldMode;
    private final String newMode;
    private final String oldObjectId;
    private final String newObjectId;
    private final ObjectType objectType;
    private final SourceCompleteness sourceCompleteness;
    private final Provenance provenance;

    public ChangedFile(String path, ChangeType changeType, String content) {
        this(path, changeType, content, null, null, null, null, null,
                ObjectType.UNKNOWN, SourceCompleteness.COMPLETE, Provenance.CALLER_ASSERTED);
    }

    public ChangedFile(String path, ChangeType changeType, String content,
                       String oldPath, String oldMode, String newMode,
                       String oldObjectId, String newObjectId, ObjectType objectType,
                       SourceCompleteness sourceCompleteness, Provenance provenance) {
        this.path = path;
        this.changeType = changeType;
        this.content = content != null ? content : "";
        this.oldPath = oldPath;
        this.oldMode = oldMode;
        this.newMode = newMode;
        this.oldObjectId = oldObjectId;
        this.newObjectId = newObjectId;
        this.objectType = objectType == null ? ObjectType.UNKNOWN : objectType;
        this.sourceCompleteness = sourceCompleteness == null ? SourceCompleteness.COMPLETE : sourceCompleteness;
        this.provenance = provenance == null ? Provenance.CALLER_ASSERTED : provenance;
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

    public String getOldPath() {
        return oldPath;
    }

    public String getOldMode() {
        return oldMode;
    }

    public String getNewMode() {
        return newMode;
    }

    public String getOldObjectId() {
        return oldObjectId;
    }

    public String getNewObjectId() {
        return newObjectId;
    }

    public ObjectType getObjectType() {
        return objectType;
    }

    public SourceCompleteness getSourceCompleteness() {
        return sourceCompleteness;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    /** True only for structure facts bound to Git by the in-process trusted adapter. */
    public boolean hasAuthoritativeGitStructure() {
        return provenance == Provenance.TRUSTED_GIT_ADAPTER
                && oldMode != null && newMode != null
                && oldObjectId != null && newObjectId != null;
    }

    public boolean isNewGitlink() {
        return hasAuthoritativeGitStructure()
                && "160000".equals(newMode)
                && !"160000".equals(oldMode)
                && objectType == ObjectType.GITLINK;
    }
}
