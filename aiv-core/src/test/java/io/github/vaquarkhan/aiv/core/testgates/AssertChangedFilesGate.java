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

package io.github.vaquarkhan.aiv.core.testgates;

import io.github.vaquarkhan.aiv.model.AIVContext;
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
        return new GateResult(getId(), passed, "expected=" + expectedCount + " actual=" + actualCount);
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

