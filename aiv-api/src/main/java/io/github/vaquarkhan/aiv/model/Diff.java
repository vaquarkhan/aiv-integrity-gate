/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aiv.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents the git diff between base and head refs.
 *
 * @author Vaquar Khan
 */
public final class Diff {

    private final String baseRef;
    private final String headRef;
    private final List<ChangedFile> changedFiles;
    private final String rawDiff;
    private final int linesAdded;
    private final int linesDeleted;
    private final String authorEmail;
    /** Latest commit on {@code headRef} contains an anchored {@code /aiv skip} directive. */
    private final boolean skipDirectivePresent;
    private final List<String> warnings;
    /** Per-path net line change (added minus deleted) from {@code git diff --numstat}. */
    private final Map<String, Integer> perFileNetLoc;

    public Diff(String baseRef, String headRef, List<ChangedFile> changedFiles, String rawDiff) {
        this(baseRef, headRef, changedFiles, rawDiff, 0, 0, null, false, List.of(), Map.of());
    }

    public Diff(String baseRef, String headRef, List<ChangedFile> changedFiles, String rawDiff,
                int linesAdded, int linesDeleted, String authorEmail, boolean skipDirectivePresent) {
        this(baseRef, headRef, changedFiles, rawDiff, linesAdded, linesDeleted, authorEmail,
                skipDirectivePresent, List.of(), Map.of());
    }

    public Diff(String baseRef, String headRef, List<ChangedFile> changedFiles, String rawDiff,
                int linesAdded, int linesDeleted, String authorEmail, boolean skipDirectivePresent,
                List<String> warnings, Map<String, Integer> perFileNetLoc) {
        this.baseRef = baseRef;
        this.headRef = headRef;
        this.changedFiles = changedFiles == null ? Collections.emptyList() : List.copyOf(changedFiles);
        this.rawDiff = rawDiff != null ? rawDiff : "";
        this.linesAdded = linesAdded;
        this.linesDeleted = linesDeleted;
        this.authorEmail = authorEmail;
        this.skipDirectivePresent = skipDirectivePresent;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        this.perFileNetLoc = perFileNetLoc == null ? Map.of() : Map.copyOf(perFileNetLoc);
    }

    public String getBaseRef() {
        return baseRef;
    }

    public String getHeadRef() {
        return headRef;
    }

    public List<ChangedFile> getChangedFiles() {
        return changedFiles;
    }

    public String getRawDiff() {
        return rawDiff;
    }

    public int getLinesAdded() {
        return linesAdded;
    }

    public int getLinesDeleted() {
        return linesDeleted;
    }

    public int getNetLoc() {
        return linesAdded - linesDeleted;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    public boolean isSkipDirectivePresent() {
        return skipDirectivePresent;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Map<String, Integer> getPerFileNetLoc() {
        return perFileNetLoc;
    }

    /** Net lines added minus deleted for one path, if known from numstat. */
    public Integer getNetLocForFile(String path) {
        if (path == null) return null;
        String norm = path.replace("\\", "/");
        return perFileNetLoc.get(norm);
    }
}
