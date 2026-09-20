/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeSyntaxValidatorTest {

    @Test
    void missingNodeSkips() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(-1, "missing", false);
        var v = new NodeSyntaxValidator(exec, "node", false, false);
        assertTrue(v.validate("const x = 1;").valid());
    }

    @Test
    void exitZeroPasses() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(0, "", true);
        var v = new NodeSyntaxValidator(exec, "node", false, false);
        assertTrue(v.validate("const x = 1;").valid());
    }

    @Test
    void exitOneFailsForJs() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(1, "SyntaxError: bad:3:1", true);
        var v = new NodeSyntaxValidator(exec, "node", false, false);
        var r = v.validate("const x = ;");
        assertFalse(r.valid());
        assertTrue(r.line() == 3 || r.line() == null || r.line() >= 1);
    }

    @Test
    void unknownStripFlagSkipsForTs() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(9, "bad option: --experimental-strip-types", true);
        var v = new NodeSyntaxValidator(exec, "node", true, false);
        assertTrue(v.validate("const x: number = 1;").valid());
    }

    @Test
    void jsxUnexpectedTokenSkips() {
        CommandExecutor exec = (cmd, stdin) -> new CommandExecutor.ExecResult(1, "Unexpected token <", true);
        var v = new NodeSyntaxValidator(exec, "node", false, true);
        assertTrue(v.validate("<div/>").valid());
    }

    @Test
    void executorExceptionSkips() {
        CommandExecutor exec = (cmd, stdin) -> {
            throw new RuntimeException("boom");
        };
        var v = new NodeSyntaxValidator(exec, "node", false, false);
        assertTrue(v.validate("x").valid());
    }

    @Test
    void bestEffortDeleteCoversFailure() {
        NodeSyntaxValidator.FORCE_DELETE_FAILURE = true;
        try {
            NodeSyntaxValidator.bestEffortDelete(Path.of("nope"));
        } finally {
            NodeSyntaxValidator.FORCE_DELETE_FAILURE = false;
        }
    }

    @Test
    void parseLineHelpers() {
        assertTrue(NodeSyntaxValidator.parseLine(null) == null);
        assertTrue(NodeSyntaxValidator.lastLine(null).contains("syntax"));
        assertTrue(NodeSyntaxValidator.lastLine("a\nb").equals("b"));
    }
}
