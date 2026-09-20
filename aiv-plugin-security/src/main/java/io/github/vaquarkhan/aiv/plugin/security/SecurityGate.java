/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.security;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-precision secret / credential leak tripwire on <em>added</em> lines only.
 * Off by default; enable with {@code gates: [{ id: security, enabled: true }]}.
 *
 * <p>Vendor prefixes and private-key headers are forever-on shapes. Generic assignment
 * and high-entropy password/secret values require an explicit keyword on the same line
 * so ordinary UUIDs / hashes in code do not fail the gate.
 */
public final class SecurityGate implements QualityGate {

    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("(AKIA|ASIA)[0-9A-Z]{16}");
    private static final Pattern GITHUB_PAT = Pattern.compile("(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{20,}");
    private static final Pattern GITHUB_FINE_PAT = Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b");
    private static final Pattern SLACK_TOKEN = Pattern.compile("xox[baprs]-[0-9A-Za-z-]{10,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");
    private static final Pattern STRIPE_LIVE = Pattern.compile("\\bsk_live_[0-9a-zA-Z]{16,}\\b");
    private static final Pattern NPM_TOKEN = Pattern.compile("\\bnpm_[A-Za-z0-9]{20,}\\b");
    private static final Pattern GOOGLE_API_KEY = Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{20,}\\b");
    private static final Pattern OPENAI_PROJ = Pattern.compile("\\bsk-proj-[A-Za-z0-9_\\-]{20,}\\b");
    private static final Pattern ANTHROPIC_KEY = Pattern.compile("\\bsk-ant-[A-Za-z0-9_\\-]{20,}\\b");
    private static final Pattern SENDGRID_KEY = Pattern.compile("\\bSG\\.[A-Za-z0-9_\\-]{16,}\\.[A-Za-z0-9_\\-]{16,}\\b");
    private static final Pattern GENERIC_ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token)\\s*[=:]\\s*['\"][^'\"]{12,}['\"]");
    /** Keyword-bound high-entropy values (password / secret / token assignments). */
    private static final Pattern ENTROPY_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|client_secret|private_token)\\s*[=:]\\s*['\"]([^'\"]{20,})['\"]");

    /** Minimum Shannon entropy (bits/char) for {@link #ENTROPY_ASSIGNMENT} values. */
    static final double ENTROPY_THRESHOLD = 4.5;

    @Override
    public String getId() {
        return "security";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        List<String> failures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Map<String, Set<String>> added = parseAddedLines(context.getDiff().getRawDiff());

        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            String content = f.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            boolean scanAll = f.getChangeType() == ChangedFile.ChangeType.ADDED
                    || added.isEmpty()
                    || !added.containsKey(norm(f.getPath()));
            Set<String> lines = scanAll ? null : added.get(norm(f.getPath()));
            scan(f.getPath(), content, lines, failures, findings);
        }
        if (failures.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("\n", failures), findings);
    }

    private static void scan(
            String path, String content, Set<String> onlyAdded, List<String> failures, List<Finding> findings) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (onlyAdded != null && !onlyAdded.contains(line)) {
                continue;
            }
            if (AWS_ACCESS_KEY.matcher(line).find()) {
                hit(path, i + 1, "security.aws-key", "AWS access key id on added line in " + path,
                        failures, findings);
            }
            if (GITHUB_PAT.matcher(line).find() || GITHUB_FINE_PAT.matcher(line).find()) {
                hit(path, i + 1, "security.github-token", "GitHub token on added line in " + path,
                        failures, findings);
            }
            if (SLACK_TOKEN.matcher(line).find()) {
                hit(path, i + 1, "security.slack-token", "Slack token on added line in " + path,
                        failures, findings);
            }
            if (PRIVATE_KEY.matcher(line).find()) {
                hit(path, i + 1, "security.private-key", "Private key block on added line in " + path,
                        failures, findings);
            }
            if (STRIPE_LIVE.matcher(line).find()) {
                hit(path, i + 1, "security.stripe-live", "Stripe live secret key on added line in " + path,
                        failures, findings);
            }
            if (NPM_TOKEN.matcher(line).find()) {
                hit(path, i + 1, "security.npm-token", "npm token on added line in " + path,
                        failures, findings);
            }
            if (GOOGLE_API_KEY.matcher(line).find()) {
                hit(path, i + 1, "security.google-api-key", "Google API key on added line in " + path,
                        failures, findings);
            }
            if (OPENAI_PROJ.matcher(line).find()) {
                hit(path, i + 1, "security.openai-key", "OpenAI project API key on added line in " + path,
                        failures, findings);
            }
            if (ANTHROPIC_KEY.matcher(line).find()) {
                hit(path, i + 1, "security.anthropic-key", "Anthropic API key on added line in " + path,
                        failures, findings);
            }
            if (SENDGRID_KEY.matcher(line).find()) {
                hit(path, i + 1, "security.sendgrid-key", "SendGrid API key on added line in " + path,
                        failures, findings);
            }
            if (GENERIC_ASSIGNED_SECRET.matcher(line).find()) {
                hit(path, i + 1, "security.assigned-secret",
                        "Hard-coded secret assignment on added line in " + path, failures, findings);
            }
            Matcher ent = ENTROPY_ASSIGNMENT.matcher(line);
            if (ent.find()) {
                String value = ent.group(2);
                if (shannonEntropy(value) >= ENTROPY_THRESHOLD && !looksLikePlaceholder(value)) {
                    hit(path, i + 1, "security.high-entropy-secret",
                            "High-entropy secret assignment on added line in " + path, failures, findings);
                }
            }
        }
    }

    private static void hit(
            String path, int line, String rule, String msg, List<String> failures, List<Finding> findings) {
        failures.add(msg);
        findings.add(Finding.atLine(rule, path, line, msg));
    }

    /** Shannon entropy in bits per character. */
    static double shannonEntropy(String s) {
        if (s == null || s.isEmpty()) {
            return 0.0;
        }
        int[] counts = new int[256];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 256) {
                counts[c]++;
                n++;
            }
        }
        if (n == 0) {
            return 0.0;
        }
        double h = 0.0;
        for (int count : counts) {
            if (count == 0) {
                continue;
            }
            double p = (double) count / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    static boolean looksLikePlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("example")
                || lower.contains("placeholder")
                || lower.contains("changeme")
                || lower.contains("your_")
                || lower.contains("xxx")
                || lower.matches("0+")
                || lower.matches("x+");
    }

    static Map<String, Set<String>> parseAddedLines(String rawDiff) {
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
                if ("/dev/null".equals(rest)) {
                    current = null;
                } else {
                    current = norm(rest);
                    map.computeIfAbsent(current, k -> new HashSet<>());
                }
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                map.get(current).add(line.substring(1));
            }
        }
        return map;
    }

    private static String norm(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
