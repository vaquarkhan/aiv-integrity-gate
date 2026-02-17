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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.aiv.model.AIVResult;
import org.apache.aiv.model.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class StdoutReportPublisherTest {

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachListAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(StdoutReportPublisher.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        logger.setLevel(Level.INFO);
    }

    @Test
    void publishLogsReport() {
        var publisher = new StdoutReportPublisher();
        var result = new AIVResult(true, List.of(GateResult.pass("density")));
        publisher.publish(result);
        String output = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + b);
        assertTrue(output.contains("PASS"));
        assertTrue(output.contains("density"));
    }

    @Test
    void publishShowsFailWhenNotPassed() {
        var publisher = new StdoutReportPublisher();
        var result = new AIVResult(false, List.of(GateResult.fail("design", "forbidden")));
        publisher.publish(result);
        String output = listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + b);
        assertTrue(output.contains("FAIL"));
        assertTrue(output.contains("forbidden"));
    }
}
