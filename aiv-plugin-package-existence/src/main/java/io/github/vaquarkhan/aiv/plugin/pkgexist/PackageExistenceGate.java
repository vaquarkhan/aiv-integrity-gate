/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.pkgexist;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Hard gate: every package a change newly declares must exist in its public registry.
 *
 * <p>Why: code LLMs hallucinate package names at 5 to 20 percent of generations (Spracklen et al.,
 * USENIX Security 2025; 2026 frontier replication still 4.6 to 6.1 percent, with 53 identically
 * hallucinated names still registrable). A hallucinated dependency is either a guaranteed install
 * failure or a slopsquatting supply-chain hole. Both are objective, reproducible, and decidable with
 * one registry lookup, which makes this the rare slop class that deserves a hard block.
 *
 * <p>Honesty rule: a registry that cannot be reached is reported as {@code UNVERIFIED}, never as pass.
 * The gate still passes (precision-first) but says so in its message, and {@code strict: true} turns
 * UNVERIFIED into a failure for air-gap-aware pipelines.
 *
 * Config keys: {@code strict} (default false), {@code allow} (list of names never checked),
 * {@code pypi_url}, {@code npm_url}, {@code maven_url}, {@code timeout_seconds} (default 5).
 */
public final class PackageExistenceGate implements QualityGate {

    public static final String ID = "package-existence";
    static final String RULE_UNKNOWN = "package-existence.unknown-package";
    static final String RULE_UNVERIFIED = "package-existence.unverified";

    private final Function<Map<String, Object>, RegistryClient> clientFactory;

    public PackageExistenceGate() {
        this(PackageExistenceGate::defaultClient);
    }

    PackageExistenceGate(Function<Map<String, Object>, RegistryClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Map<String, Object> cfg = context.getConfig().findGate(ID).map(g -> g.getConfig()).orElse(Map.of());
        boolean strict = Boolean.TRUE.equals(cfg.get("strict"));
        Set<String> allow = lowerSet(cfg.get("allow"));

        Map<String, Set<String>> addedByPath = addedLinesByPath(context.getDiff().getRawDiff());
        boolean hasIndex = !addedByPath.isEmpty();

        List<DependencyDeclarations.Decl> decls = new ArrayList<>();
        List<String> declPaths = new ArrayList<>();
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            String path = norm(f.getPath());
            if (!DependencyDeclarations.isManifest(path)) {
                continue;
            }
            boolean fileIsAdded = f.getChangeType() == ChangedFile.ChangeType.ADDED;
            Set<String> added = addedByPath.getOrDefault(path, Set.of());
            if (!fileIsAdded && hasIndex && added.isEmpty()) {
                continue; // manifest listed but no hunk for it: nothing was added
            }
            // Without any diff index (in-memory diffs) a MODIFIED manifest is scanned whole: precision-first
            // is preserved because only registry-confirmed 404s fail.
            boolean scanWhole = fileIsAdded || !hasIndex;
            for (DependencyDeclarations.Decl d : DependencyDeclarations.extract(path, f.getContent(), added, scanWhole)) {
                if (allow.contains(d.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                decls.add(d);
                declPaths.add(path);
            }
        }
        if (decls.isEmpty()) {
            return GateResult.pass(ID);
        }

        RegistryClient client = clientFactory.apply(cfg);
        List<Finding> unknown = new ArrayList<>();
        List<Finding> unverified = new ArrayList<>();
        Map<String, Optional<Boolean>> cache = new HashMap<>();
        int confirmed = 0;
        for (int i = 0; i < decls.size(); i++) {
            DependencyDeclarations.Decl d = decls.get(i);
            String key = d.ecosystem() + ":" + d.name();
            Optional<Boolean> exists = cache.computeIfAbsent(key, k -> client.exists(d.ecosystem(), d.name()));
            String path = declPaths.get(i);
            if (exists.isEmpty()) {
                unverified.add(Finding.atLine(RULE_UNVERIFIED, path, d.line(),
                        "UNVERIFIED: registry for " + d.ecosystem() + " unreachable; could not confirm '" + d.name() + "' exists"));
            } else if (!exists.get()) {
                unknown.add(Finding.atLine(RULE_UNKNOWN, path, d.line(),
                        "Dependency '" + d.name() + "' does not exist in the " + d.ecosystem()
                                + " registry (hallucinated or typo; slopsquatting risk)"));
            } else {
                confirmed++;
            }
        }

        if (!unknown.isEmpty()) {
            List<Finding> all = new ArrayList<>(unknown);
            all.addAll(unverified);
            return GateResult.fail(ID, join(all), all);
        }
        if (!unverified.isEmpty()) {
            if (strict) {
                return GateResult.fail(ID, join(unverified), unverified);
            }
            return new GateResult(ID, true, "PASS with " + unverified.size() + " UNVERIFIED (registry unreachable); "
                    + confirmed + " confirmed. " + join(unverified), false, unverified);
        }
        return new GateResult(ID, true, confirmed + " newly declared dependenc" + (confirmed == 1 ? "y" : "ies")
                + " confirmed in registry", false, List.of());
    }

    static RegistryClient defaultClient(Map<String, Object> cfg) {
        int timeout = cfg.get("timeout_seconds") instanceof Number n ? n.intValue() : 5;
        return new HttpRegistryClient(str(cfg.get("pypi_url")), str(cfg.get("npm_url")), str(cfg.get("maven_url")),
                Duration.ofSeconds(timeout));
    }

    static Map<String, Set<String>> addedLinesByPath(String rawDiff) {
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

    private static String join(List<Finding> fs) {
        List<String> msgs = new ArrayList<>();
        for (Finding f : fs) {
            msgs.add(f.getFilePath() + ":" + f.getStartLine() + " " + f.getMessage());
        }
        return String.join("\n", msgs);
    }

    private static Set<String> lowerSet(Object v) {
        Set<String> out = new HashSet<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(o.toString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String norm(String p) {
        return p == null ? "" : p.replace('\\', '/');
    }
}
