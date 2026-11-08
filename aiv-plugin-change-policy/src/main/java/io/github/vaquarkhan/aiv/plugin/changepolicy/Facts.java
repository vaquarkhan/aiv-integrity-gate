/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fact names the policy language accepts, and rendering helpers. A fact missing from the map is
 * unknown in this run (for example PR metadata in a local run), except per-category and per-area
 * counts, which are zero when absent.
 *
 * @author Vaquar Khan
 */
final class Facts {

    static final String BY_CATEGORY = "files.by_category.";
    static final String BY_AREA = "files.by_area.";

    static final Set<String> NAMES = Set.of(
            "files.changed", "files.reviewable", "files.added", "files.deleted", "files.renamed",
            "lines.changed", "lines.added", "lines.deleted", "lines.source_changed", "lines.test_changed",
            "lines.docs_changed", "lines.non_test_changed", "lines.total_changed",
            "code.large_new_files", "code.max_function_lines", "code.stub_functions",
            "scope.areas", "scope.area_list", "churn.whitespace_only_files",
            "tests.files_changed", "tests.functions_added", "tests.source_areas_without_tests",
            "budget.exceeded", "budget.detail",
            "intent.class", "meta.labels", "meta.author_association", "meta.author_tier", "meta.is_draft",
            "meta.linked_issue", "meta.body_words", "template.sections_missing", "template.missing_list",
            "claims.tests_count", "claims.tests_overclaim", "claims.tests_added", "claims.ai_disclosure_mismatch",
            "git.commits", "git.behind_base", "git.commits_without_body", "git.conventional_prefix_commits");

    private Facts() {
    }

    static boolean isKnown(String name) {
        if (name.startsWith(BY_CATEGORY)) {
            return Classifier.CATEGORIES.contains(name.substring(BY_CATEGORY.length()));
        }
        return NAMES.contains(name) || (name.startsWith(BY_AREA) && name.length() > BY_AREA.length());
    }

    static Object lookup(Map<String, Object> facts, String name) {
        Object v = facts.get(name);
        if (v == null && (name.startsWith(BY_CATEGORY) || name.startsWith(BY_AREA))) {
            return 0;
        }
        return v;
    }

    /** Replaces {@code {name}} with a fact or param value; unknown names render as {@code ?}. */
    static String render(String template, Map<String, Object> facts, Map<String, Object> params) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            int close = open < 0 ? -1 : template.indexOf('}', open);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            String key = template.substring(open + 1, close);
            Object v = Facts.isKnown(key) ? lookup(facts, key) : params.get(key);
            out.append(v == null ? "?" : sanitize(v));
            i = close + 1;
        }
        return out.toString();
    }

    static String sanitize(Object v) {
        String s = v instanceof List<?> l ? String.join(", ", l.stream().map(Object::toString).toList()) : v.toString();
        s = s.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /** Compact JSON object for the {@code change-policy.facts} note and calibration tools. */
    static String toJson(Map<String, Object> facts) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : facts.entrySet()) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String value(Object v) {
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof List<?> l) {
            return "[" + String.join(",", l.stream().map(o -> quote(o.toString())).toList()) + "]";
        }
        return quote(v.toString());
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
