/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.rules;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a rule document (YAML text or already-parsed maps) into compiled {@link Rule}s.
 *
 * <pre>
 * name: airflow                      # optional; pack rules get ids rules.&lt;name&gt;.&lt;id&gt;
 * params:                            # defaults, overridable from gate config "params"
 *   known_conn_ids: []
 *   example_dag_id_pattern: "^(example|test)_"
 * defaults:                          # applied to every rule unless the rule sets the key
 *   paths: ["**&#47;*.py"]
 *   file_filter: "^\\s*(from|import)\\s+airflow"
 * rules:
 *   - id: start-date-now
 *     kind: pattern
 *     pattern: "start_date\\s*=\\s*datetime\\.now\\("
 *     message: "start_date shifts on every parse"
 * </pre>
 *
 * Strings may reference params as {@code ${name}}. A list param interpolates as a quoted alternation
 * {@code (?:a|b)} and an empty list as {@code (?!)} (matches nothing). {@code allowed_param: name} reads an
 * allowlist from params.
 */
final class RuleLoader {

    private static final Pattern PARAM = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(\\|raw)?}");
    private static final Set<String> KINDS = Set.of("pattern", "call", "hollow-function");

    private RuleLoader() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseYaml(String text) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text == null ? "" : text);
        if (root == null) {
            return Map.of();
        }
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("rules document must be a mapping");
        }
        return (Map<String, Object>) root;
    }

    /** Compiles a document; {@code paramOverrides} win over the document's own {@code params}. */
    @SuppressWarnings("unchecked")
    static List<Rule> compile(Map<String, Object> doc, Map<String, Object> paramOverrides, String fallbackName) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (doc.get("params") instanceof Map<?, ?> m) {
            params.putAll((Map<String, Object>) m);
        }
        if (paramOverrides != null) {
            params.putAll(paramOverrides);
        }
        Map<String, Object> defaults = doc.get("defaults") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        String name = doc.get("name") == null ? fallbackName : doc.get("name").toString().trim();
        String prefix = name == null || name.isBlank() ? "" : name + ".";
        List<Rule> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : list(doc.get("rules"))) {
            if (!(o instanceof Map<?, ?> rm)) {
                throw new IllegalArgumentException("each rule must be a mapping");
            }
            Map<String, Object> merged = new LinkedHashMap<>(defaults);
            merged.putAll((Map<String, Object>) rm);
            Rule r = compileRule(merged, params, prefix);
            if (!seen.add(r.id())) {
                throw new IllegalArgumentException("duplicate rule id " + r.id());
            }
            out.add(r);
        }
        return out;
    }

    static Rule compileRule(Map<String, Object> m, Map<String, Object> params, String prefix) {
        String id = str(m.get("id"), params);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("rule without id");
        }
        String kind = m.get("kind") == null ? "pattern" : str(m.get("kind"), params).toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("rule " + id + ": unknown kind '" + kind + "' (pattern, call, hollow-function)");
        }
        List<Pattern> paths = new ArrayList<>();
        for (String g : strings(m.get("paths"), params)) {
            paths.add(Globs.compile(g));
        }
        Pattern fileFilter = regex(m.get("file_filter"), params, id);
        boolean addedOnly = !Boolean.FALSE.equals(m.get("added_lines_only"));
        Pattern pattern = regex(m.get("pattern"), params, id);
        int captureGroup = m.get("capture_group") instanceof Number n ? n.intValue() : -1;
        Pattern call = regex(m.get("call"), params, id);
        String ifArg = str(m.get("if_arg"), params);
        String requireArg = str(m.get("require_arg"), params);
        Pattern capture = regex(m.get("capture"), params, id);
        Pattern captureMatches = regex(m.get("capture_matches"), params, id);
        Set<String> allowed = null;
        if (m.containsKey("allowed")) {
            allowed = new LinkedHashSet<>(strings(m.get("allowed"), params));
        } else if (m.get("allowed_param") != null) {
            allowed = new LinkedHashSet<>(strings(params.get(m.get("allowed_param").toString()), params));
        }
        boolean skipWhenEmpty = !Boolean.FALSE.equals(m.get("skip_when_allowed_empty"));
        Pattern function = regex(m.get("function"), params, id);
        String message = m.get("message") == null ? "rule " + id + " matched" : str(m.get("message"), params);
        boolean advisory = "warn".equalsIgnoreCase(String.valueOf(m.get("severity")));

        switch (kind) {
            case "pattern" -> require(pattern != null, id, "pattern needs 'pattern'");
            case "call" -> {
                require(call != null, id, "call needs 'call'");
                require(requireArg != null || capture != null, id, "call needs 'require_arg' or 'capture'");
            }
            default -> require(function != null, id, "hollow-function needs 'function'");
        }
        if (capture != null || captureGroup >= 0) {
            require(captureMatches != null || allowed != null, id, "a capture needs 'capture_matches' or 'allowed'");
        }
        return new Rule(prefix + id, kind, paths, fileFilter, addedOnly, pattern, captureGroup, call, ifArg, requireArg,
                capture, captureMatches, allowed, skipWhenEmpty, function, message, advisory);
    }

    private static void require(boolean ok, String id, String msg) {
        if (!ok) {
            throw new IllegalArgumentException("rule " + id + ": " + msg);
        }
    }

    private static Pattern regex(Object v, Map<String, Object> params, String id) {
        if (v == null) {
            return null;
        }
        String s = str(v, params);
        try {
            return Pattern.compile(s);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("rule " + id + ": bad regex '" + s + "': " + e.getDescription());
        }
    }

    /** Interpolates {@code ${param}} references. */
    static String str(Object v, Map<String, Object> params) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        Matcher m = PARAM.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            Object p = params.get(m.group(1));
            if (p == null) {
                throw new IllegalArgumentException("unknown param '" + m.group(1) + "' in '" + s + "'");
            }
            boolean raw = m.group(2) != null;
            m.appendReplacement(b, Matcher.quoteReplacement(p instanceof List<?> l ? alternation(l, raw) : p.toString()));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** {@code ${list}} quotes every item (literals); {@code ${list|raw}} joins items as regex fragments. */
    private static String alternation(List<?> items, boolean raw) {
        if (items.isEmpty()) {
            return "(?!)";
        }
        StringBuilder b = new StringBuilder("(?:");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                b.append('|');
            }
            String item = String.valueOf(items.get(i));
            b.append(raw ? "(?:" + item + ")" : Pattern.quote(item));
        }
        return b.append(')').toString();
    }

    static List<String> strings(Object v, Map<String, Object> params) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) {
            if (o != null && !o.toString().isBlank()) {
                out.add(str(o, params).trim());
            }
        }
        return out;
    }

    static List<?> list(Object v) {
        if (v instanceof List<?> l) {
            return l;
        }
        return v == null ? List.of() : List.of(v);
    }
}
