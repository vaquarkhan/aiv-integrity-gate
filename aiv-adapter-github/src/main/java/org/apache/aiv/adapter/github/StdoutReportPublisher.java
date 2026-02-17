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

package org.apache.aiv.adapter.github;

import org.apache.aiv.model.AIVResult;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.ReportPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes AIV result via logging. CI captures from log output.
 *
 * @author Vaquar Khan
 */
public final class StdoutReportPublisher implements ReportPublisher {

    private static final Logger log = LoggerFactory.getLogger(StdoutReportPublisher.class);

    @Override
    public void publish(AIVResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== AIV Report ===\n");
        sb.append("Overall: ").append(result.isPassed() ? "PASS" : "FAIL").append("\n");
        for (GateResult gr : result.getGateResults()) {
            sb.append("  ").append(gr.getGateId()).append(": ")
                    .append(gr.isPassed() ? "PASS" : "FAIL");
            if (!gr.getMessage().isEmpty()) {
                sb.append(" - ").append(gr.getMessage());
            }
            sb.append("\n");
        }
        sb.append("==================\n");
        log.info(sb.toString());
    }
}
