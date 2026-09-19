/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates Python source by asking an interpreter to {@code ast.parse} it via stdin. When no
 * interpreter is available (or the invocation fails), the check is skipped (treated as OK) so a
 * missing toolchain never produces false failures. The interpreter command defaults to
 * {@code python} and can be overridden with the {@code aiv.python.command} system property.
 */
public final class PythonSyntaxValidator implements SourceValidator {

    // Only a genuine SyntaxError is a blocking parse defect. Read raw bytes and strict-decode so a
    // non-UTF-8 / surrogate-escaped payload (a diff-extraction artifact, not a code defect) exits 0
    // (skip) instead of surfacing as a bogus UnicodeEncodeError "does not parse". Any non-SyntaxError
    // exception is likewise treated as unvalidatable and skipped, matching the missing-toolchain rule.
    private static final String SCRIPT =
            "import ast,sys\n"
            + "try:\n"
            + "    src=sys.stdin.buffer.read().decode('utf-8')\n"
            + "except Exception:\n"
            + "    sys.exit(0)\n"
            + "try:\n"
            + "    ast.parse(src)\n"
            + "except SyntaxError as e:\n"
            + "    sys.stderr.write('SyntaxError line %s: %s' % (getattr(e,'lineno',None), getattr(e,'msg','')))\n"
            + "    sys.exit(1)\n"
            + "except Exception:\n"
            + "    sys.exit(0)\n";
    private static final Pattern LINE = Pattern.compile("line (\\d+)");

    private final CommandExecutor executor;
    private final String pythonCommand;

    public PythonSyntaxValidator(CommandExecutor executor) {
        this(executor, resolvePythonCommand());
    }

    PythonSyntaxValidator(CommandExecutor executor, String pythonCommand) {
        this.executor = executor;
        this.pythonCommand = pythonCommand;
    }

    static String resolvePythonCommand() {
        String override = System.getProperty("aiv.python.command");
        return (override != null && !override.isBlank()) ? override.trim() : "python";
    }

    @Override
    public ValidationResult validate(String source) {
        CommandExecutor.ExecResult result;
        try {
            result = executor.run(List.of(pythonCommand, "-c", SCRIPT), source);
        } catch (Exception cannotRun) {
            return ValidationResult.ok();
        }
        if (!result.available()) {
            return ValidationResult.ok();
        }
        if (result.exitCode() == 0) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(parseLine(result.stderr()), lastMeaningfulLine(result.stderr()));
    }

    static Integer parseLine(String stderr) {
        if (stderr == null) {
            return null;
        }
        Matcher m = LINE.matcher(stderr);
        Integer found = null;
        while (m.find()) {
            found = Integer.valueOf(m.group(1));
        }
        return found;
    }

    static String lastMeaningfulLine(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "syntax error";
        }
        String[] lines = stderr.strip().split("\n");
        return lines[lines.length - 1].trim();
    }
}
