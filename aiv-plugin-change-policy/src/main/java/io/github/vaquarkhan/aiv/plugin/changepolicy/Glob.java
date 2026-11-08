/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Strict path globs for file classification. {@code **&#47;} matches zero or more directories,
 * {@code **} matches anything, {@code *} and {@code ?} stay inside one path segment. A pattern without
 * a slash matches the file name in any directory. Unlike {@code PathFilter}, there is no substring
 * fallback, so {@code docs/**} never matches {@code src/docs_util.py}.
 *
 * @author Vaquar Khan
 */
final class Glob {

    private Glob() {
    }

    static Pattern compile(String glob) {
        String g = glob.trim().replace('\\', '/');
        StringBuilder re = new StringBuilder(g.contains("/") ? "" : "(?:.*/)?");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                boolean slash = i + 2 < g.length() && g.charAt(i + 2) == '/';
                re.append(slash ? "(?:.*/)?" : ".*");
                i += slash ? 2 : 1;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }

    static List<Pattern> compileAll(List<String> globs) {
        List<Pattern> out = new ArrayList<>();
        for (String g : globs) {
            out.add(compile(g));
        }
        return out;
    }

    static boolean any(String path, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }
}
