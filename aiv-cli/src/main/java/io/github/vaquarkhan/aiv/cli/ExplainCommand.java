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
        String requested = id.trim().toLowerCase();
        InputStream in = openBestEffortTopic(requested);
        try (InputStream stream = in) {
            if (stream == null) {
                System.err.println("No embedded help for \"" + id + "\".");
                System.err.println("Built-in topics: density, design, dependency, doc-integrity, invariant.");
                System.err.println("Project-specific design rule IDs live in .aiv/design-rules.yaml (constraint id); "
                        + "there is no bundled markdown for those.");
                return 1;
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
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

    private static InputStream openBestEffortTopic(String requested) {
        InputStream direct = openTopic(requested);
        if (direct != null) {
            return direct;
        }
        int idx = requested.lastIndexOf('.');
        while (idx > 0) {
            String prefix = requested.substring(0, idx);
            InputStream prefixed = openTopic(prefix);
            if (prefixed != null) {
                return prefixed;
            }
            idx = requested.lastIndexOf('.', idx - 1);
        }
        return null;
    }

    private static InputStream openTopic(String id) {
        return resourceLoader.getResourceAsStream("explain/" + id + ".md");
    }
}
