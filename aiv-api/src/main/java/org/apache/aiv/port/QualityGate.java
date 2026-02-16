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

package org.apache.aiv.port;

import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.GateResult;

/**
 * SPI for quality gates. Each plugin implements this and is discovered via ServiceLoader.
 *
 * @author Vaquar Khan
 */
public interface QualityGate {

    /**
     * Unique id for this gate, e.g. "density", "design", "invariant".
     */
    String getId();

    /**
     * Evaluate the context and return pass/fail.
     */
    GateResult evaluate(AIVContext context);
}
