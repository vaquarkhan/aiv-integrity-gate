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

package org.apache.aiv.model;

import java.util.Collections;
import java.util.List;

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
    private final boolean skipRequested;

    public Diff(String baseRef, String headRef, List<ChangedFile> changedFiles, String rawDiff) {
        this(baseRef, headRef, changedFiles, rawDiff, 0, 0, null, false);
    }

    public Diff(String baseRef, String headRef, List<ChangedFile> changedFiles, String rawDiff,
                int linesAdded, int linesDeleted, String authorEmail, boolean skipRequested) {
        this.baseRef = baseRef;
        this.headRef = headRef;
        this.changedFiles = changedFiles == null ? Collections.emptyList() : List.copyOf(changedFiles);
        this.rawDiff = rawDiff != null ? rawDiff : "";
        this.linesAdded = linesAdded;
        this.linesDeleted = linesDeleted;
        this.authorEmail = authorEmail;
        this.skipRequested = skipRequested;
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

    public boolean isSkipRequested() {
        return skipRequested;
    }
}
