/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonSyntaxValidatorTest {

    private static PythonSyntaxValidator withExecutor(CommandExecutor exec) {
        return new PythonSyntaxValidator(exec, "python");
    }

    @Test
    void okWhenExitZero() {
        var v = withExecutor((cmd, stdin) -> new CommandExecutor.ExecResult(0, "", true));
        assertTrue(v.validate("print(1)").valid());
    }

    @Test
    void skipWhenInterpreterUnavailable() {
        var v = withExecutor((cmd, stdin) -> new CommandExecutor.ExecResult(-1, "not found", false));
        assertTrue(v.validate("print(1)").valid());
    }

    @Test
    void skipWhenExecutorThrows() {
        var v = withExecutor((cmd, stdin) -> { throw new IllegalStateException("boom"); });
        assertTrue(v.validate("print(1)").valid());
    }

    @Test
    void failWhenExitNonZero() {
        String stderr = "  File \"<stdin>\", line 3\n    def f(:\nSyntaxError: invalid syntax";
        var v = withExecutor((cmd, stdin) -> new CommandExecutor.ExecResult(1, stderr, true));
        ValidationResult r = v.validate("def f(:");
        assertFalse(r.valid());
        assertEquals(3, r.line());
        assertEquals("SyntaxError: invalid syntax", r.detail());
    }

    @Test
    void publicConstructorResolvesCommand() {
        var v = new PythonSyntaxValidator((cmd, stdin) -> new CommandExecutor.ExecResult(0, "", true));
        assertTrue(v.validate("x=1").valid());
    }

    @Test
    void resolvePythonCommandDefaultAndOverride() {
        System.clearProperty("aiv.python.command");
        assertEquals("python", PythonSyntaxValidator.resolvePythonCommand());
        System.setProperty("aiv.python.command", "py");
        try {
            assertEquals("py", PythonSyntaxValidator.resolvePythonCommand());
        } finally {
            System.clearProperty("aiv.python.command");
        }
    }

    @Test
    void parseLineHandlesNullAndMissingAndPresent() {
        assertNull(PythonSyntaxValidator.parseLine(null));
        assertNull(PythonSyntaxValidator.parseLine("no numbers here"));
        assertEquals(12, PythonSyntaxValidator.parseLine("File x, line 5\nFile y, line 12"));
    }

    @Test
    void lastMeaningfulLineHandlesBlankAndNormal() {
        assertEquals("syntax error", PythonSyntaxValidator.lastMeaningfulLine(null));
        assertEquals("syntax error", PythonSyntaxValidator.lastMeaningfulLine("   "));
        assertEquals("SyntaxError: bad", PythonSyntaxValidator.lastMeaningfulLine("line 1\nSyntaxError: bad"));
    }

    @Test
    void listCommandShapeIsUsable() {
        boolean[] called = {false};
        CommandExecutor capture = (List<String> cmd, String stdin) -> {
            called[0] = cmd.size() == 3;
            return new CommandExecutor.ExecResult(0, "", true);
        };
        withExecutor(capture).validate("x=1");
        assertTrue(called[0]);
    }
}
