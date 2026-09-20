/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates JavaScript / TypeScript with {@code node --check} when Node is available.
 * Missing Node → skip (OK). JSX/TSX without strip-types support → skip (precision-first).
 */
public final class NodeSyntaxValidator implements SourceValidator {

    private static final Pattern LINE = Pattern.compile("(?:Error|error).*?:(\\d+)|:(\\d+)");

    private final CommandExecutor executor;
    private final String nodeCommand;
    private final boolean typescript;
    private final boolean jsx;

    public NodeSyntaxValidator(CommandExecutor executor, boolean typescript, boolean jsx) {
        this(executor, resolveNodeCommand(), typescript, jsx);
    }

    NodeSyntaxValidator(CommandExecutor executor, String nodeCommand, boolean typescript, boolean jsx) {
        this.executor = executor;
        this.nodeCommand = nodeCommand;
        this.typescript = typescript;
        this.jsx = jsx;
    }

    static String resolveNodeCommand() {
        String override = System.getProperty("aiv.node.command");
        return (override != null && !override.isBlank()) ? override.trim() : "node";
    }

    @Override
    public ValidationResult validate(String source) {
        String suffix = jsx ? (typescript ? ".tsx" : ".jsx") : (typescript ? ".ts" : ".js");
        Path tmp = null;
        try {
            tmp = Files.createTempFile("aiv-syntax-", suffix);
            Files.writeString(tmp, source, StandardCharsets.UTF_8);
            List<String> cmd;
            if (typescript || jsx) {
                // Node 22+: strip types; JSX still often fails — unavailable/exit≠0 with strip flag missing → skip
                cmd = List.of(nodeCommand, "--experimental-strip-types", "--check", tmp.toString());
            } else {
                cmd = List.of(nodeCommand, "--check", tmp.toString());
            }
            CommandExecutor.ExecResult result = executor.run(cmd, "");
            if (!result.available()) {
                return ValidationResult.ok();
            }
            if (result.exitCode() == 0) {
                return ValidationResult.ok();
            }
            // Unknown flag / experimental not supported → treat as skip for TS/JSX only
            String err = result.stderr() == null ? "" : result.stderr();
            if ((typescript || jsx) && (err.contains("bad option") || err.contains("unknown option")
                    || err.contains("Experimental") || err.contains("not supported"))) {
                return ValidationResult.ok();
            }
            if (jsx && err.toLowerCase().contains("unexpected token")) {
                // Classic JSX without transform — do not false-fail
                return ValidationResult.ok();
            }
            return ValidationResult.fail(parseLine(err), lastLine(err));
        } catch (Exception e) {
            return ValidationResult.ok();
        } finally {
            if (tmp != null) {
                bestEffortDelete(tmp);
            }
        }
    }

    /** Package-private for tests. */
    static boolean FORCE_DELETE_FAILURE = false;

    static void bestEffortDelete(Path tmp) {
        try {
            if (FORCE_DELETE_FAILURE) {
                throw new IOException("forced");
            }
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    static Integer parseLine(String stderr) {
        if (stderr == null) {
            return null;
        }
        Matcher m = LINE.matcher(stderr);
        Integer found = null;
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            if (g != null) {
                found = Integer.valueOf(g);
            }
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
