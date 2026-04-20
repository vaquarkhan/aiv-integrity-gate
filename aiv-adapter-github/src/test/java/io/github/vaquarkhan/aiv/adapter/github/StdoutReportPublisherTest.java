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

package io.github.vaquarkhan.aiv.adapter.github;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    }

    @Test
    void publishPrintsNoticesToStdout() {
        var publisher = new StdoutReportPublisher();
        var result = new AIVResult(true, List.of(GateResult.pass("density")), List.of("large file skipped"));
        var capture = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            publisher.publish(result);
        } finally {
            System.setOut(prev);
        }
        assertTrue(capture.toString(StandardCharsets.UTF_8).contains("WARN: large file skipped"));
    }

    @Test
    void publishShowsAdvisoryForNonBlockingFailure() {
        var publisher = new StdoutReportPublisher();
        var result = new AIVResult(true, List.of(GateResult.advisory("density", "low signal")));
        var capture = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            publisher.publish(result);
        } finally {
            System.setOut(prev);
        }
        assertTrue(capture.toString(StandardCharsets.UTF_8).contains("ADVISORY"));
    }

    @Test
    void publishOmitsDashWhenGateMessageEmpty() {
        var publisher = new StdoutReportPublisher();
        var result = new AIVResult(true, List.of(new GateResult("g", true, "")));
        var capture = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            publisher.publish(result);
        } finally {
            System.setOut(prev);
        }
        String text = capture.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("g: PASS"));
        assertFalse(text.contains("g: PASS -"));
    }

    @Test
    void publishPrintsDoctorHeaderWhenDoctorNoticePresent() {
        var publisher = new StdoutReportPublisher();
        var result = new AIVResult(true, List.of(GateResult.pass("density")),
                List.of("[DOCTOR] Informational run"));
        var capture = new ByteArrayOutputStream();
        PrintStream prev = System.out;
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            publisher.publish(result);
        } finally {
            System.setOut(prev);
        }
        assertTrue(capture.toString(StandardCharsets.UTF_8).contains("AIV Report (DOCTOR)"));
    }
}
