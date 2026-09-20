/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoSyntaxValidatorTest {

    @Test
    void missingGofmtSkips() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(-1, "x", false);
        assertTrue(new GoSyntaxValidator(exec, "gofmt").validate("package main").valid());
    }

    @Test
    void exitZeroPasses() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(0, "", true);
        assertTrue(new GoSyntaxValidator(exec, "gofmt").validate("package main\n").valid());
    }

    @Test
    void exitOneFails() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(2, "stdin:2:1: expected ';'", true);
        var r = new GoSyntaxValidator(exec, "gofmt").validate("package main\nfunc {");
        assertFalse(r.valid());
        assertEquals(2, r.line());
    }

    @Test
    void exceptionSkips() {
        CommandExecutor exec = (cmd, stdin) -> {
            throw new Exception("nope");
        };
        assertTrue(new GoSyntaxValidator(exec, "gofmt").validate("x").valid());
    }

    @Test
    void helpers() {
        assertTrue(GoSyntaxValidator.parseLine(null) == null);
        assertTrue(GoSyntaxValidator.lastLine("").contains("syntax"));
    }
}
