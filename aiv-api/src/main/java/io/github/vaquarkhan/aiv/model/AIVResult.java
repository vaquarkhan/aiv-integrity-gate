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

/**
 * Aggregated result from all gates. Overall pass/fail and individual gate results.
 *
 * @author Vaquar Khan
 */
public final class AIVResult {

    private final boolean passed;
    private final List<GateResult> gateResults;
    private final List<String> notices;

    public AIVResult(boolean passed, List<GateResult> gateResults) {
        this(passed, gateResults, List.of());
    }

    public AIVResult(boolean passed, List<GateResult> gateResults, List<String> notices) {
        this.passed = passed;
        this.gateResults = gateResults == null ? Collections.emptyList() : List.copyOf(gateResults);
        this.notices = notices == null ? List.of() : List.copyOf(notices);
    }

    public boolean isPassed() {
        return passed;
    }

    public List<GateResult> getGateResults() {
        return gateResults;
    }

    public List<String> getNotices() {
        return notices;
    }
}
