/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.rules;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One compiled rule. Kinds:
 * <ul>
 *   <li>{@code pattern}: {@link #pattern} is searched on each (added) line. With {@link #captureGroup} the
 *       captured text is the value; {@link #captureMatches} fires only when the value matches it, and
 *       {@link #allowed} fires only when the value is not in the list.</li>
 *   <li>{@code call}: {@link #call} names a callee; the whole call text (balanced parentheses, across lines)
 *       is examined. {@link #ifArg} restricts to calls that pass that argument, {@link #requireArg} fires when
 *       the argument is absent, {@link #capture} extracts a value for {@link #captureMatches} / {@link #allowed}.</li>
 *   <li>{@code hollow-function}: a function whose name matches {@link #function} and whose body contains only
 *       no-op statements (pass, return, ..., raise NotImplementedError, throw new UnsupportedOperationException,
 *       TODO comments). Python indentation bodies and brace bodies are both understood.</li>
 * </ul>
 */
record Rule(String id, String kind, List<Pattern> paths, Pattern fileFilter, boolean addedLinesOnly,
            Pattern pattern, int captureGroup, Pattern call, String ifArg, String requireArg, Pattern capture,
            Pattern captureMatches, Set<String> allowed, boolean skipWhenAllowedEmpty, Pattern function,
            String message, boolean advisory) {

    boolean appliesTo(String path) {
        if (paths.isEmpty()) {
            return true;
        }
        for (Pattern p : paths) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }
}
