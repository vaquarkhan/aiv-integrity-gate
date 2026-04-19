/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Prints offline help for gate / rule ids from classpath {@code explain/*.md}.
 *
 * @author Vaquar Khan
 */
public final class ExplainCommand {

    /** Overridable for tests; reset to {@code ExplainCommand.class.getClassLoader()} after. */
    static ClassLoader resourceLoader = ExplainCommand.class.getClassLoader();

    private ExplainCommand() {
    }

    public static int run(String id) {
        if (id == null || id.isBlank()) {
            System.err.println("Usage: java -jar aiv-cli.jar explain <gate-or-topic-id>");
            return 1;
        }
        String path = "explain/" + id.trim().toLowerCase() + ".md";
        try (InputStream in = resourceLoader.getResourceAsStream(path)) {
            if (in == null) {
                System.err.println("No embedded help for \"" + id + "\". Try: density, design, dependency, doc-integrity, invariant");
                return 1;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.print(text);
            if (!text.endsWith("\n")) {
                System.out.println();
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Could not read help: " + e.getMessage());
            return 1;
        }
    }
}
