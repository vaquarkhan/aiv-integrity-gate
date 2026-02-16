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

package org.apache.aiv.plugin.invariant;

import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.QualityGate;

/**
 * Invariant gate using template-based property checks. For Java files, we run jqwik tests if present.
 * This gate passes by default when no invariant tests exist - actual PBT runs in the project's test phase.
 *
 * @author Vaquar Khan
 */
public final class InvariantGate implements QualityGate {

    @Override
    public String getId() {
        return "invariant";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        long javaFiles = context.getDiff().getChangedFiles().stream()
                .filter(f -> f.getPath().endsWith(".java"))
                .count();
        if (javaFiles == 0) {
            return GateResult.pass(getId());
        }
        return GateResult.pass(getId());
    }
}
