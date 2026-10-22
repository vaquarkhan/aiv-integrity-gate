/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.duplication;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns source text into a list of significant, whitespace-normalized lines with their 1-based
 * original line numbers. Blank lines, comment-only lines, brace/punctuation-only lines, and
 * import/package statements are dropped so that clones are matched on logic, not boilerplate.
 */
final class LineNormalizer {

    /** A normalized line and where it came from. */
    record Line(String text, int lineNumber) {
    }

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern NOISE = Pattern.compile(
            "^(?:[{}()\\[\\];,]*|//.*|#.*|\\*.*|/\\*.*|\\*/.*|import\\s.*|from\\s+\\S+\\s+import\\s.*"
                    + "|package\\s.*|using\\s.*|@\\w+(\\(.*\\))?|else|try|finally|end|pass|return;?|break;?|continue;?)$");

    private LineNormalizer() {
    }

    static List<Line> normalize(String content) {
        List<Line> out = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return out;
        }
        String[] raw = content.split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String t = WS.matcher(raw[i].trim()).replaceAll(" ");
            if (t.length() < 4 || NOISE.matcher(t).matches()) {
                continue;
            }
            out.add(new Line(t, i + 1));
        }
        return out;
    }

    /** Joins {@code window} consecutive normalized lines starting at {@code from}. */
    static String windowKey(List<Line> lines, int from, int window) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + window; i++) {
            sb.append(lines.get(i).text()).append('\u0001');
        }
        return sb.toString();
    }
}
