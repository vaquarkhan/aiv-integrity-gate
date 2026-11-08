/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.changepolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured policy condition: {@code all}, {@code any}, {@code not}, or a leaf
 * {@code {fact: <name>, <op>: <value>}}. Parsing validates fact names, operators and
 * {@code ${param}} references, so configuration errors surface even for conditions that never run.
 * Evaluation is three-valued: a missing fact makes its leaf UNKNOWN, and UNKNOWN propagates the way
 * SQL NULL does through AND, OR and NOT.
 *
 * @author Vaquar Khan
 */
sealed interface Condition permits Condition.All, Condition.Any, Condition.Not, Condition.Leaf {

    enum Tri { TRUE, FALSE, UNKNOWN }

    Set<String> OPS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "not_in", "contains");

    Tri eval(Map<String, Object> facts, Set<String> unknown);

    record All(List<Condition> parts) implements Condition {
        @Override
        public Tri eval(Map<String, Object> facts, Set<String> unknown) {
            Tri out = Tri.TRUE;
            for (Condition c : parts) {
                Tri t = c.eval(facts, unknown);
                if (t == Tri.FALSE) {
                    return Tri.FALSE;
                }
                if (t == Tri.UNKNOWN) {
                    out = Tri.UNKNOWN;
                }
            }
            return out;
        }
    }

    record Any(List<Condition> parts) implements Condition {
        @Override
        public Tri eval(Map<String, Object> facts, Set<String> unknown) {
            Tri out = Tri.FALSE;
            for (Condition c : parts) {
                Tri t = c.eval(facts, unknown);
                if (t == Tri.TRUE) {
                    return Tri.TRUE;
                }
                if (t == Tri.UNKNOWN) {
                    out = Tri.UNKNOWN;
                }
            }
            return out;
        }
    }

    record Not(Condition inner) implements Condition {
        @Override
        public Tri eval(Map<String, Object> facts, Set<String> unknown) {
            Tri t = inner.eval(facts, unknown);
            return t == Tri.UNKNOWN ? t : (t == Tri.TRUE ? Tri.FALSE : Tri.TRUE);
        }
    }

    record Leaf(String fact, String op, Object operand) implements Condition {
        @Override
        public Tri eval(Map<String, Object> facts, Set<String> unknown) {
            Object v = Facts.lookup(facts, fact);
            if (v == null) {
                unknown.add(fact);
                return Tri.UNKNOWN;
            }
            return compare(v) ? Tri.TRUE : Tri.FALSE;
        }

        private boolean compare(Object v) {
            switch (op) {
                case "eq":
                    return same(v, operand);
                case "ne":
                    return !same(v, operand);
                case "in":
                    return ((List<?>) operand).stream().anyMatch(o -> same(v, o));
                case "not_in":
                    return ((List<?>) operand).stream().noneMatch(o -> same(v, o));
                case "contains":
                    return v instanceof List<?> l ? l.stream().anyMatch(o -> same(o, operand))
                            : v.toString().contains(operand.toString());
                default:
                    if (!(v instanceof Number n)) {
                        throw new IllegalArgumentException("change-policy fact " + fact + " is not numeric, cannot use " + op);
                    }
                    int c = Double.compare(n.doubleValue(), ((Number) operand).doubleValue());
                    return switch (op) {
                        case "gt" -> c > 0;
                        case "gte" -> c >= 0;
                        case "lt" -> c < 0;
                        default -> c <= 0;
                    };
            }
        }

        private static boolean same(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y) {
                return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
            }
            return a.toString().equalsIgnoreCase(b.toString());
        }
    }

    static Condition parse(Object node, Map<String, Object> params, String where) {
        if (!(node instanceof Map<?, ?> m) || m.isEmpty()) {
            throw err(where, "must be a non-empty map");
        }
        if (m.containsKey("all") || m.containsKey("any")) {
            String key = m.containsKey("all") ? "all" : "any";
            if (m.size() != 1 || !(m.get(key) instanceof List<?> l) || l.isEmpty()) {
                throw err(where, "'" + key + "' must be the only key and hold a non-empty list");
            }
            List<Condition> parts = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                parts.add(parse(l.get(i), params, where + "." + key + "[" + i + "]"));
            }
            return key.equals("all") ? new All(parts) : new Any(parts);
        }
        if (m.containsKey("not")) {
            if (m.size() != 1) {
                throw err(where, "'not' must be the only key");
            }
            return new Not(parse(m.get("not"), params, where + ".not"));
        }
        Object fact = m.get("fact");
        if (fact == null || m.size() != 2) {
            throw err(where, "a leaf needs 'fact' and exactly one operator (" + String.join(", ", new java.util.TreeSet<>(OPS)) + ")");
        }
        if (!Facts.isKnown(fact.toString())) {
            throw err(where, "unknown fact '" + fact + "'");
        }
        String op = m.keySet().stream().map(Object::toString).filter(k -> !k.equals("fact")).findFirst().orElseThrow();
        if (!OPS.contains(op)) {
            throw err(where, "unknown operator '" + op + "'");
        }
        Object operand = resolve(m.get(op), params, where);
        boolean list = op.equals("in") || op.equals("not_in");
        if (list != (operand instanceof List<?>) || operand == null) {
            throw err(where, "operator '" + op + "' needs " + (list ? "a list" : "a single value"));
        }
        if (Set.of("gt", "gte", "lt", "lte").contains(op) && !(operand instanceof Number)) {
            throw err(where, "operator '" + op + "' needs a number");
        }
        return new Leaf(fact.toString(), op, operand);
    }

    private static Object resolve(Object v, Map<String, Object> params, String where) {
        if (v instanceof String s && s.startsWith("${") && s.endsWith("}")) {
            String name = s.substring(2, s.length() - 1);
            if (!params.containsKey(name)) {
                throw err(where, "unknown param '" + name + "'");
            }
            return params.get(name);
        }
        return v;
    }

    private static IllegalArgumentException err(String where, String msg) {
        return new IllegalArgumentException("change-policy " + where + ": " + msg);
    }
}
