/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates Go source with {@code gofmt} on stdin. Missing {@code gofmt} → skip (OK).
 */
public final class GoSyntaxValidator implements SourceValidator {

    private static final Pattern LINE = Pattern.compile("(?:stdin|:).*?:(\\d+):");

    private final CommandExecutor executor;
    private final String gofmtCommand;

    public GoSyntaxValidator(CommandExecutor executor) {
        this(executor, resolveGofmtCommand());
    }

    GoSyntaxValidator(CommandExecutor executor, String gofmtCommand) {
        this.executor = executor;
        this.gofmtCommand = gofmtCommand;
    }

    static String resolveGofmtCommand() {
        String override = System.getProperty("aiv.gofmt.command");
        return (override != null && !override.isBlank()) ? override.trim() : "gofmt";
    }

    @Override
    public ValidationResult validate(String source) {
        CommandExecutor.ExecResult result;
        try {
            result = executor.run(List.of(gofmtCommand), source);
        } catch (Exception e) {
            return ValidationResult.ok();
        }
        if (!result.available()) {
            return ValidationResult.ok();
        }
        if (result.exitCode() == 0) {
            return ValidationResult.ok();
        }
        return ValidationResult.fail(parseLine(result.stderr()), lastLine(result.stderr()));
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

    static String lastLine(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "syntax error";
        }
        String[] lines = stderr.strip().split("\n");
        return lines[lines.length - 1].trim();
    }
}
