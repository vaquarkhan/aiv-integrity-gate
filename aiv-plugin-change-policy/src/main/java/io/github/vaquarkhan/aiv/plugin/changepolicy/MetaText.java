/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic facts from the PR title and body: intent class, linked issue, template sections,
 * description claims about tests, and AI-disclosure consistency. Everything here is pattern based and
 * documented; nothing interprets meaning.
 *
 * @author Vaquar Khan
 */
final class MetaText {

    static final Map<String, Pattern> INTENTS = intents();
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'_-]*");
    private static final Pattern ISSUE = Pattern.compile("(?<![\\w&])#\\d+\\b|github\\.com/[\\w.-]+/[\\w.-]+/issues/\\d+");
    private static final Pattern NEXT_HEADING = Pattern.compile("(?m)^\\s{0,3}(#{1,6}\\s|\\*\\*[^*\\n]+\\*\\*\\s*$)");
    /*
     * Only claims about tests ADDED count ("added 22 test cases", "5 new tests", "12 tests added").
     * Calibration showed that counts of tests RUN ("127 tests passing", "1111 passed") are common in
     * good PRs and say nothing about the diff.
     */
    private static final Pattern CLAIM_COUNT = Pattern.compile(
            "(?i)\\b(?:added|adds|add|wrote|writes|introduced?|introduces|includes?)\\s+(\\d{1,4})\\s+"
                    + "(?:new\\s+|unit\\s+|additional\\s+|integration\\s+)*(?:test cases|tests|test)\\b"
                    + "|(?<![#\\w.])(\\d{1,4})\\s+(?:new|additional)\\s+(?:unit\\s+|integration\\s+)?(?:test cases|tests)\\b"
                    + "|(?<![#\\w.])(\\d{1,4})\\s+(?:unit\\s+|integration\\s+)?(?:test cases|tests)\\s+(?:were\\s+)?added\\b");
    private static final Pattern CLAIM_ADDED = Pattern.compile(
            "(?i)\\b(added|adds|add|wrote|written|new|includes?|included|updated?)\\b[^.\\n]{0,30}?\\b(?:unit |integration )?tests?\\b");
    /** Negations and offers ("happy to add tests", "will add tests") are not claims that tests were added. */
    private static final Pattern NEGATION = Pattern.compile(
            "(?i)\\b(no|not|without|none|nothing|never|will|would|could|can|should|if|happy to|todo|follow-?up)\\b|n't");
    private static final Pattern UNCHECKED_ITEM = Pattern.compile("^\\s*[-*]\\s*\\[\\s\\]");
    /** A filled Generated-by trailer; {@code [Tool Name]} is the unfilled template placeholder. */
    private static final Pattern TRAILER = Pattern.compile("(?im)^\\s*generated-by:\\s*(?!\\[tool name)\\S");
    private static final Pattern YES_UNCHECKED = Pattern.compile("(?im)^\\s*[-*]\\s*\\[\\s\\]\\s*yes\\b");
    private static final Pattern YES_CHECKED = Pattern.compile("(?im)^\\s*[-*]\\s*\\[[xX]\\]\\s*yes\\b");
    private static final Pattern TEST_DEF = Pattern.compile(
            "^\\s*(?:async\\s+)?def\\s+test\\w*\\s*\\(|^\\s*@(?:Test|ParameterizedTest)\\b|^\\s*func\\s+Test\\w*\\s*\\("
                    + "|^\\s*(?:it|test)(?:\\.each\\([^)]*\\))?\\s*\\(\\s*['\"`]");

    private MetaText() {
    }

    static String intent(String title) {
        for (Map.Entry<String, Pattern> e : INTENTS.entrySet()) {
            if (e.getValue().matcher(title).find()) {
                return e.getKey();
            }
        }
        return "unknown";
    }

    static boolean linkedIssue(String text, List<Pattern> extra) {
        if (ISSUE.matcher(text).find()) {
            return true;
        }
        for (Pattern p : extra) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /** Body without HTML comments and without lines matching any ignore pattern (template boilerplate). */
    static String clean(String body, List<Pattern> ignore) {
        List<String> kept = new ArrayList<>();
        for (String line : COMMENT.matcher(body).replaceAll("").split("\n")) {
            boolean drop = false;
            for (Pattern p : ignore) {
                drop |= p.matcher(line).find();
            }
            if (!drop) {
                kept.add(line);
            }
        }
        return String.join("\n", kept);
    }

    static int words(String text) {
        Matcher m = WORD.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static List<String> missingSections(String body, List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            Matcher h = Pattern.compile("(?im)^\\s{0,3}(?:#{1,6}\\s*|\\*\\*\\s*)" + Pattern.quote(name) + ".*$").matcher(body);
            if (!h.find()) {
                missing.add(name);
                continue;
            }
            String rest = body.substring(h.end());
            Matcher next = NEXT_HEADING.matcher(rest);
            if (words(next.find() ? rest.substring(0, next.start()) : rest) == 0) {
                missing.add(name);
            }
        }
        return missing;
    }

    static int testsClaimCount(String body) {
        int max = 0;
        Matcher m = CLAIM_COUNT.matcher(body);
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            max = Math.max(max, Integer.parseInt(n));
        }
        return max;
    }

    static boolean testsAddedClaim(String body) {
        for (String line : body.split("\n")) {
            if (UNCHECKED_ITEM.matcher(line).find()) {
                continue;
            }
            Matcher m = CLAIM_ADDED.matcher(line);
            while (m.find()) {
                String around = line.substring(Math.max(0, m.start() - 25), m.end());
                if (!NEGATION.matcher(around).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean disclosureMismatch(String body) {
        // Only "tool named, box left unchecked" is a contradiction. A ticked box with the tool named after
        // "Yes:" instead of in the trailer is a normal way to fill the template (calibration finding).
        return TRAILER.matcher(body).find() && YES_UNCHECKED.matcher(body).find() && !YES_CHECKED.matcher(body).find();
    }

    static int testFunctions(List<String> addedLines) {
        int n = 0;
        for (String l : addedLines) {
            if (TEST_DEF.matcher(l).find()) {
                n++;
            }
        }
        return n;
    }

    private static Map<String, Pattern> intents() {
        Map<String, Pattern> m = new LinkedHashMap<>();
        m.put("typo", Pattern.compile("(?i)\\b(typos?|spelling|misspell\\w*|grammar)\\b"));
        m.put("docs", Pattern.compile("(?i)\\b(docs?|documentation|docstrings?|readme)\\b"));
        m.put("bump", Pattern.compile("(?i)\\b(bump|bumps|bumped|upgrade|upgrades)\\b"));
        m.put("test", Pattern.compile("(?i)^\\s*(\\[?tests?\\]?:|\\[tests?\\])|\\b(add|improve|fix)\\s+(unit\\s+)?tests?\\b"));
        m.put("refactor", Pattern.compile("(?i)\\b(refactor\\w*|cleanup|clean up|rename\\w*)\\b"));
        m.put("fix", Pattern.compile("(?i)\\b(fix(es|ed|ing)?|bug|minor|small|tweak|nit|correct)\\b"));
        m.put("feature", Pattern.compile("(?i)\\b(add|adds|added|feat|feature|support|implement\\w*|introduce\\w*|new)\\b"));
        return java.util.Collections.unmodifiableMap(m);
    }
}
