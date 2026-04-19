/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import io.github.vaquarkhan.aiv.adapter.git.GitDiffProvider;
import io.github.vaquarkhan.aiv.adapter.github.StdoutReportPublisher;
import io.github.vaquarkhan.aiv.cli.config.DocChecksConfigProvider;
import io.github.vaquarkhan.aiv.cli.config.YamlConfigProvider;
import io.github.vaquarkhan.aiv.core.Orchestrator;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.port.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.function.IntConsumer;

/**
 * CLI entry point.
 *
 * @author Vaquar Khan
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    static IntConsumer EXIT = System::exit;

    public static void main(String[] args) {
        EXIT.accept(run(args));
    }

    static int run(String[] args) {
        for (String a : args) {
            if ("--version".equals(a) || "-V".equals(a)) {
                String v = cliVersion();
                System.out.println("aiv-cli " + v);
                return 0;
            }
        }

        if (args.length > 0 && "init".equals(args[0])) {
            try {
                return InitCommand.run(parseWorkspace(tail(args)));
            } catch (IOException e) {
                log.error("init failed: {}", e.getMessage());
                return 1;
            }
        }
        if (args.length > 0 && "explain".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("Usage: java -jar aiv-cli.jar explain <gate-id>");
                return 1;
            }
            return ExplainCommand.run(args[1]);
        }

        boolean doctor = args.length > 0 && "doctor".equals(args[0]);
        String[] gateArgs = doctor ? tail(args) : args;

        Path workspace = Paths.get(".").toAbsolutePath();
        String baseRef = "origin/main";
        String headRef = "HEAD";
        boolean includeDocChecks = false;
        Path jsonOutputPath = null;
        int warningsExitCode = 0;

        for (int i = 0; i < gateArgs.length; i++) {
            if ("--workspace".equals(gateArgs[i]) && i + 1 < gateArgs.length) {
                workspace = Paths.get(gateArgs[++i]).toAbsolutePath();
            } else if ("--diff".equals(gateArgs[i]) && i + 1 < gateArgs.length) {
                baseRef = gateArgs[++i];
            } else if ("--head".equals(gateArgs[i]) && i + 1 < gateArgs.length) {
                headRef = gateArgs[++i];
            } else if ("--include-doc-checks".equals(gateArgs[i])) {
                includeDocChecks = true;
            } else if ("--doctor".equals(gateArgs[i])) {
                doctor = true;
            } else if ("--output-json".equals(gateArgs[i]) && i + 1 < gateArgs.length) {
                jsonOutputPath = Paths.get(gateArgs[++i]).toAbsolutePath();
            } else if ("--warnings-exit-code".equals(gateArgs[i]) && i + 1 < gateArgs.length) {
                try {
                    warningsExitCode = Integer.parseInt(gateArgs[++i].trim());
                } catch (NumberFormatException ex) {
                    log.error("Invalid --warnings-exit-code (expected integer)");
                    return 2;
                }
            }
        }

        try {
            var diffProvider = new GitDiffProvider();
            ConfigProvider configProvider = new YamlConfigProvider();
            if (includeDocChecks) {
                configProvider = new DocChecksConfigProvider(configProvider, true);
            }
            var recording = new RecordingReportPublisher(new StdoutReportPublisher());
            var orchestrator = new Orchestrator(diffProvider, configProvider, recording);
            int coreExit = orchestrator.run(workspace, baseRef, headRef, doctor);
            AIVResult last = recording.getLastResult();
            if (jsonOutputPath != null && last != null) {
                try {
                    Path parent = jsonOutputPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    JsonReportWriter.write(last, jsonOutputPath, cliVersion());
                } catch (IOException e) {
                    log.error("Could not write --output-json: {}", e.getMessage());
                    return 2;
                }
            }
            if (doctor) {
                return 0;
            }
            return mergeWarningsExit(coreExit, warningsExitCode, last);
        } catch (IllegalArgumentException e) {
            log.error("AIV error: {}", e.getMessage());
            return 2;
        } catch (IllegalStateException e) {
            log.error("AIV git error: {}", e.getMessage());
            return 3;
        }
    }

    /**
     * When the run passed but the diff carried warnings, optionally map exit to {@code warningsExitCode} for CI.
     */
    static int mergeWarningsExit(int coreExit, int warningsExitCode, AIVResult last) {
        if (coreExit == 0 && warningsExitCode > 0 && last != null && last.isPassed()
                && !last.getNotices().isEmpty()) {
            return warningsExitCode;
        }
        return coreExit;
    }

    private static String[] tail(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] r = new String[args.length - 1];
        System.arraycopy(args, 1, r, 0, r.length);
        return r;
    }

    private static Path parseWorkspace(String[] args) {
        Path workspace = Paths.get(".").toAbsolutePath();
        for (int i = 0; i < args.length; i++) {
            if ("--workspace".equals(args[i]) && i + 1 < args.length) {
                workspace = Paths.get(args[++i]).toAbsolutePath();
            }
        }
        return workspace;
    }

    static String cliVersion() {
        return readVersionFromStream(Main.class.getClassLoader().getResourceAsStream("META-INF/aiv-cli.properties"));
    }

    static String readVersionFromStream(InputStream in) {
        try (InputStream stream = in) {
            if (stream == null) {
                return "dev";
            }
            Properties p = new Properties();
            p.load(stream);
            String v = p.getProperty("version", "").trim();
            return v.isEmpty() ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }
}
