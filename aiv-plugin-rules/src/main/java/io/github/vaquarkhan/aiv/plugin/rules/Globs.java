/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.rules;

import java.util.regex.Pattern;

/** Minimal glob to regex: {@code **} any path, {@code *} within a segment, {@code ?} one character. Forward slashes only. */
final class Globs {

    private Globs() {
    }

    static Pattern compile(String glob) {
        StringBuilder r = new StringBuilder();
        String g = glob.replace('\\', '/');
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < g.length() && g.charAt(i + 1) == '/') {
                        i++;
                        r.append("(?:.*/)?");
                    } else {
                        r.append(".*");
                    }
                } else {
                    r.append("[^/]*");
                }
            } else if (c == '?') {
                r.append("[^/]");
            } else {
                r.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(r.toString());
    }
}
