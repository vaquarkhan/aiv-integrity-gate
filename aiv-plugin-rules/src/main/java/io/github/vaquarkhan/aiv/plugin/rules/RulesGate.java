/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.rules;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generic, configuration-driven rule gate. Nothing in this module knows about any particular framework;
 * framework knowledge lives in rule packs (YAML on the classpath under {@code rulepacks/}) and in the
 * project's own rule files.
 *
 * <pre>
 * - id: rules
 *   enabled: true
 *   severity: warn
 *   config:
 *     packs: [airflow]                 # shipped packs: rulepacks/&lt;name&gt;.yaml
 *     rules_files: [".aiv/rules/*.yaml"]   # project rule documents (globs, relative to the workspace)
 *     rules: []                        # inline rules, same schema
 *     params:                          # override any pack/document param
 *       known_conn_ids: [aws_default]
 *     disabled_rules: [airflow.missing-catchup]
 * </pre>
 *
 * Rule ids are {@code rules.&lt;pack&gt;.&lt;id&gt;} for pack rules and {@code rules.&lt;id&gt;} for project rules.
 */
public final class RulesGate implements QualityGate {

    public static final String ID = "rules";

    /** Seam for tests: resolves a pack name to its YAML text, or null when the pack does not exist. */
    @FunctionalInterface
    interface PackSource {
        String read(String name) throws IOException;
    }

    private final PackSource packs;

    public RulesGate() {
        this(RulesGate::classpathPack);
    }

    RulesGate(PackSource packs) {
        this.packs = packs;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GateResult evaluate(AIVContext context) {
        Map<String, Object> cfg = context.getConfig().findGate(ID).map(g -> g.getConfig()).orElse(Map.of());
        Map<String, Object> params = cfg.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        List<Rule> rules = loadRules(cfg, params, context.getPolicyWorkspace());
        Set<String> disabled = new HashSet<>();
        for (String d : RuleLoader.strings(cfg.get("disabled_rules"), Map.of())) {
            disabled.add(d.toLowerCase(Locale.ROOT).replaceFirst("^" + ID + "\\.", ""));
        }
        rules.removeIf(r -> disabled.contains(r.id().toLowerCase(Locale.ROOT)));
        if (rules.isEmpty()) {
            return new GateResult(ID, true, "No rules configured", false, List.of());
        }

        Map<String, Set<String>> added = addedLines(context.getDiff().getRawDiff());
        List<Finding> blocking = new ArrayList<>();
        List<Finding> advisory = new ArrayList<>();
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            String path = norm(f.getPath());
            Set<String> addedHere = f.getChangeType() == ChangedFile.ChangeType.ADDED || added.isEmpty()
                    ? null : added.getOrDefault(path, Set.of());
            for (RuleEngine.Hit h : RuleEngine.scan(path, f.getContent(), addedHere, rules)) {
                Finding fd = Finding.atLine(ID + "." + h.rule().id(), path, h.line(), h.message());
                (h.rule().advisory() ? advisory : blocking).add(fd);
            }
        }
        if (blocking.isEmpty() && advisory.isEmpty()) {
            return GateResult.pass(ID);
        }
        List<Finding> all = new ArrayList<>(blocking);
        all.addAll(advisory);
        List<String> msgs = new ArrayList<>();
        for (Finding fd : all) {
            msgs.add(fd.getFilePath() + ":" + fd.getStartLine() + " [" + fd.getRuleId() + "] " + fd.getMessage());
        }
        String summary = String.join("\n", msgs);
        return blocking.isEmpty() ? GateResult.advisory(ID, summary, all) : GateResult.fail(ID, summary, all);
    }

    List<Rule> loadRules(Map<String, Object> cfg, Map<String, Object> params, Path workspace) {
        List<Rule> rules = new ArrayList<>();
        for (String name : RuleLoader.strings(cfg.get("packs"), Map.of())) {
            String text;
            try {
                text = packs.read(name.toLowerCase(Locale.ROOT));
            } catch (IOException e) {
                throw new IllegalArgumentException("rules: cannot read pack '" + name + "': " + e.getMessage(), e);
            }
            if (text == null) {
                throw new IllegalArgumentException("rules: unknown pack '" + name + "'");
            }
            rules.addAll(RuleLoader.compile(RuleLoader.parseYaml(text), params, name));
        }
        for (String glob : RuleLoader.strings(cfg.get("rules_files"), Map.of())) {
            List<Path> matched = resolve(workspace, glob);
            // A missing plain path or an empty glob is an optional overlay (the airflow preset
            // lists .aiv/rules/*.yaml before a project has added any local rules).
            if (matched.isEmpty()) {
                continue;
            }
            for (Path file : matched) {
                try {
                    rules.addAll(RuleLoader.compile(RuleLoader.parseYaml(Files.readString(file)), params, null));
                } catch (IOException e) {
                    throw new IllegalArgumentException("rules: cannot read " + file + ": " + e.getMessage(), e);
                }
            }
        }
        if (cfg.get("rules") != null) {
            rules.addAll(RuleLoader.compile(Map.of("rules", cfg.get("rules")), params, null));
        }
        return rules;
    }

    /** Files under {@code workspace} matching a forward-slash glob; a plain path matches itself when present. */
    static List<Path> resolve(Path workspace, String glob) {
        List<Path> out = new ArrayList<>();
        String g = glob.replace('\\', '/');
        if (!g.contains("*") && !g.contains("?")) {
            Path p = workspace.resolve(g);
            if (Files.isRegularFile(p)) {
                out.add(p);
            }
            return out;
        }
        Pattern pat = Globs.compile(g);
        try (Stream<Path> walk = Files.walk(workspace)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> pat.matcher(norm(workspace.relativize(p).toString())).matches())
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new IllegalArgumentException("rules: cannot scan " + workspace + ": " + e.getMessage(), e);
        }
        return out;
    }

    static String classpathPack(String name) throws IOException {
        if (!name.matches("[a-z0-9][a-z0-9_-]*")) {
            return null;
        }
        try (InputStream in = RulesGate.class.getClassLoader().getResourceAsStream("rulepacks/" + name + ".yaml")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Map<String, Set<String>> addedLines(String rawDiff) {
        Map<String, Set<String>> map = new HashMap<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return map;
        }
        String current = null;
        for (String line : rawDiff.split("\n", -1)) {
            if (line.startsWith("+++ ")) {
                String rest = line.substring(4).trim();
                if (rest.startsWith("b/")) {
                    rest = rest.substring(2);
                }
                current = "/dev/null".equals(rest) ? null : norm(rest);
                if (current != null) {
                    map.computeIfAbsent(current, k -> new HashSet<>());
                }
                continue;
            }
            if (current != null && line.startsWith("+") && !line.startsWith("+++")) {
                map.get(current).add(line.substring(1).trim());
            }
        }
        return map;
    }

    static String norm(String p) {
        return p == null ? "" : p.replace('\\', '/');
    }
}
