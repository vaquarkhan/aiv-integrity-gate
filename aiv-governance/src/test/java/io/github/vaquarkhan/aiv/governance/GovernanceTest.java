/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.governance;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.audit.VerifyResult;
import io.github.vaquarkhan.aiv.governance.ConcernOutcome.Mode;
import io.github.vaquarkhan.aiv.governance.ConcernOutcome.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Vaquar Khan
 */
class GovernanceTest {

    @Test
    void outcomeStoresFieldsAndNormalizesNullDetail() {
        var withDetail = new ConcernOutcome("secrets", "gitleaks", Mode.DELEGATED, Status.PASS, "0 findings");
        assertEquals("secrets", withDetail.getConcern());
        assertEquals("gitleaks", withDetail.getProvider());
        assertEquals(Mode.DELEGATED, withDetail.getMode());
        assertEquals(Status.PASS, withDetail.getStatus());
        assertEquals("0 findings", withDetail.getDetail());

        var nullDetail = new ConcernOutcome("provenance", "aiv", Mode.BUILTIN, Status.WARN, null);
        assertEquals("", nullDetail.getDetail());
    }

    @Test
    void enumsAreReferenced() {
        assertNotNull(Mode.values());
        assertEquals(Mode.SKIPPED, Mode.valueOf("SKIPPED"));
        assertNotNull(Status.values());
        assertEquals(Status.FAIL, Status.valueOf("FAIL"));
    }

    @Test
    void routerPrefersDelegateThenBuiltinThenSkip() {
        assertEquals(Mode.DELEGATED, ConcernRouter.resolveMode(true, true));
        assertEquals(Mode.DELEGATED, ConcernRouter.resolveMode(true, false));
        assertEquals(Mode.BUILTIN, ConcernRouter.resolveMode(false, true));
        assertEquals(Mode.SKIPPED, ConcernRouter.resolveMode(false, false));
    }

    @Test
    void overallIsFailWhenAnyFails() {
        var list = List.of(
                new ConcernOutcome("a", "t", Mode.DELEGATED, Status.PASS, ""),
                new ConcernOutcome("b", "t", Mode.BUILTIN, Status.FAIL, "boom"));
        assertEquals(Status.FAIL, GovernanceReport.overall(list));
    }

    @Test
    void overallIsWarnWhenAnyWarnsAndNoneFail() {
        var list = List.of(
                new ConcernOutcome("a", "t", Mode.DELEGATED, Status.PASS, ""),
                new ConcernOutcome("b", "aiv", Mode.BUILTIN, Status.WARN, "advisory"));
        assertEquals(Status.WARN, GovernanceReport.overall(list));
    }

    @Test
    void overallIsPassWhenAllPassAndForEmpty() {
        assertEquals(Status.PASS, GovernanceReport.overall(List.of(
                new ConcernOutcome("a", "t", Mode.DELEGATED, Status.PASS, ""))));
        assertEquals(Status.PASS, GovernanceReport.overall(List.of()));
    }

    @Test
    void renderProducesMarkdownForEmptyAndNonEmpty() {
        assertTrue(GovernanceReport.render(List.of()).contains("Overall: PASS"));
        String md = GovernanceReport.render(List.of(
                new ConcernOutcome("secrets", "gitleaks", Mode.DELEGATED, Status.PASS, "clean")));
        assertTrue(md.contains("| secrets | gitleaks | DELEGATED | PASS | clean |"));
    }

    @Test
    void canonicalIsDeterministicForEmptyAndNonEmpty() {
        assertEquals("overall=PASS", GovernanceReport.canonical(List.of()));
        String c = GovernanceReport.canonical(List.of(
                new ConcernOutcome("provenance", "aiv", Mode.BUILTIN, Status.WARN, "x")));
        assertTrue(c.startsWith("overall=WARN"));
        assertTrue(c.contains("provenance|aiv|BUILTIN|WARN"));
    }

    @Test
    void recordAppendsToTamperEvidentAudit(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("gov-audit.log");
        String head = GovernanceAudit.record(log, List.of(
                new ConcernOutcome("secrets", "gitleaks", Mode.DELEGATED, Status.PASS, "clean")));
        assertNotNull(head);
        GovernanceAudit.record(log, List.of(
                new ConcernOutcome("provenance", "aiv", Mode.BUILTIN, Status.WARN, "no disclosure")));
        VerifyResult v = HashChainAudit.verify(log);
        assertTrue(v.isIntact());
        assertEquals(2, v.getRecordCount());
    }

    @Test
    void privateConstructorsAreExercised() throws Exception {
        for (Class<?> c : new Class<?>[]{ConcernRouter.class, GovernanceReport.class, GovernanceAudit.class}) {
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        }
    }
}
