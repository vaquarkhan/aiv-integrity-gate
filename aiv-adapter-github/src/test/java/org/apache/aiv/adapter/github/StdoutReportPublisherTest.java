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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class StdoutReportPublisherTest {

    @Test
    void publishWritesToStdout() {
        var out = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            var publisher = new StdoutReportPublisher();
            var result = new AIVResult(true, List.of(GateResult.pass("density")));
            publisher.publish(result);
            String output = out.toString();
            assertTrue(output.contains("PASS"));
            assertTrue(output.contains("density"));
        } finally {
            System.setOut(prev);
        }
    }

    @Test
    void publishShowsFailWhenNotPassed() {
        var out = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(out));
        try {
            var publisher = new StdoutReportPublisher();
            var result = new AIVResult(false, List.of(GateResult.fail("design", "forbidden")));
            publisher.publish(result);
            String output = out.toString();
            assertTrue(output.contains("FAIL"));
            assertTrue(output.contains("forbidden"));
        } finally {
            System.setOut(prev);
        }
    }
}
