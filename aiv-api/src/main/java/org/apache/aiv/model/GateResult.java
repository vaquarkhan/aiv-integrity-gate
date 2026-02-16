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

/**
 * Result from a single quality gate evaluation.
 *
 * @author Vaquar Khan
 */
public final class GateResult {

    private final String gateId;
    private final boolean passed;
    private final String message;

    public GateResult(String gateId, boolean passed, String message) {
        this.gateId = gateId;
        this.passed = passed;
        this.message = message != null ? message : "";
    }

    public static GateResult pass(String gateId) {
        return new GateResult(gateId, true, "OK");
    }

    public static GateResult fail(String gateId, String message) {
        return new GateResult(gateId, false, message);
    }

    public String getGateId() {
        return gateId;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getMessage() {
        return message;
    }
}
