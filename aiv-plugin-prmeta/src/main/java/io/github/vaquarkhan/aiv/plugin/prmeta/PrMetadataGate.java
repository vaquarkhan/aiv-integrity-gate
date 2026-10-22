/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.prmeta;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Deterministic pull-request metadata hygiene gate.
 *
 * <p>Checks the objective, string-decidable subset of the pull-request quality criteria that Apache
 * projects document (descriptive title, meaningful description, no bare conventional-commit prefix,
 * optional Gen-AI disclosure presence). It never judges whether a description is "meaningful" in a
 * semantic sense - that is left to human or LLM review. Precision-first: when the title and body are
 * not supplied to the run (local pre-commit, or a CI that does not export them) the gate passes.
 *
 * <p>Inputs are resolved from {@link AIVContext#getMetadata()} first (keys {@code pr_title},
 * {@code pr_body}, {@code pr_template}) and then from environment variables {@code AIV_PR_TITLE},
 * {@code AIV_PR_BODY}, {@code AIV_PR_TEMPLATE}. A GitHub Action populates the environment variables
 * from the pull-request event.
 *
 * @author Vaquar Khan
 */
public final class PrMetadataGate implements QualityGate {

    /** Gate id used in {@code .aiv/config.yaml} and reports. */
    public static final String ID = "pr-metadata";

    private static final Pattern CONVENTIONAL_PREFIX = Pattern.compile(
            "(?i)^(feat|fix|chore|docs|style|refactor|perf|test|build|ci|revert)(\\([^)]*\\))?!?:\\s");

    private static final Pattern ISSUE_ONLY = Pattern.compile(
            "(?i)^(fix(es|ed)?|close[sd]?|resolve[sd]?)?\\s*#\\d+$");

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    /** Generic multi-word titles that carry no information about the change. */
    private static final Set<String> GENERIC_TITLES = Set.of(
            "fix bug", "fix bugs", "bug fix", "update code", "code update", "fix issue",
            "fix issues", "minor changes", "minor fixes", "small fixes", "some changes",
            "update files", "misc changes", "various changes");

    private static final String[] DISCLOSURE_MARKERS = {
            "ai usage disclosure", "gen-ai", "genai", "generative ai", "generative-ai",
            "ai-assisted", "ai assisted", "assisted-by:", "generated-by:",
            "substantial_ai_assistance", "ai-generated", "ai generated"};

    private final UnaryOperator<String> env;

    /** Production constructor: reads fallback inputs from the process environment. */
    public PrMetadataGate() {
        this(System::getenv);
    }

    /** Package-private seam so tests can inject environment lookups. */
    PrMetadataGate(UnaryOperator<String> env) {
        this.env = env == null ? k -> null : env;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Map<String, Object> md = context.getMetadata();
        String title = resolve(md, "pr_title", "AIV_PR_TITLE");
        String body = resolve(md, "pr_body", "AIV_PR_BODY");
        String template = resolve(md, "pr_template", "AIV_PR_TEMPLATE");

        if (title == null && body == null) {
            return GateResult.pass(ID);
        }

        AIVConfig.GateConfig cfg = context.getConfig().findGate(ID).orElse(null);
        int minTitleWords = intConfig(cfg, "min_title_words", 2);
        boolean requireDisclosure = boolConfig(cfg, "require_disclosure", false);

        List<String> failures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        if (title != null) {
            checkTitle(title.trim(), minTitleWords, failures, findings);
        }
        if (body != null) {
            checkBody(title, body, template, failures, findings);
        }
        if (requireDisclosure && !hasDisclosure(body)) {
            add(failures, findings, "pr-metadata.missing-disclosure",
                    "Gen-AI disclosure is required by project policy but was not found in the PR description");
        }

        if (failures.isEmpty()) {
            return GateResult.pass(ID);
        }
        return GateResult.fail(ID, String.join("\n", failures), findings);
    }

    private void checkTitle(String title, int minTitleWords, List<String> failures, List<Finding> findings) {
        if (title.isEmpty()) {
            add(failures, findings, "pr-metadata.empty-title", "PR title is empty");
            return;
        }
        if (CONVENTIONAL_PREFIX.matcher(title).find()) {
            add(failures, findings, "pr-metadata.title-prefix",
                    "Do not use conventional-commit prefixes in the title (e.g. 'feat:'); state the change directly");
        }
        if (ISSUE_ONLY.matcher(title).matches()) {
            add(failures, findings, "pr-metadata.title-issue-only",
                    "Title only references an issue number; describe the change instead");
        }
        if (GENERIC_TITLES.contains(stripTrailingPunctuation(title).toLowerCase())) {
            add(failures, findings, "pr-metadata.title-generic",
                    "Title is generic and does not describe the change: '" + title + "'");
        }
        if (countWords(title) < minTitleWords) {
            add(failures, findings, "pr-metadata.title-too-short",
                    "Title is too short to describe the change (needs at least " + minTitleWords + " words)");
        }
    }

    private void checkBody(String title, String body, String template,
                           List<String> failures, List<Finding> findings) {
        if (body.isBlank()) {
            add(failures, findings, "pr-metadata.empty-body",
                    "PR description is empty; explain what the change does and why");
            return;
        }
        if (title != null && body.trim().equalsIgnoreCase(title.trim())) {
            add(failures, findings, "pr-metadata.body-repeats-title",
                    "PR description just repeats the title; add real detail");
            return;
        }
        if (isTemplateOnly(body, template)) {
            add(failures, findings, "pr-metadata.body-template-only",
                    "PR description contains only the template boilerplate; fill it in");
        }
    }

    private static boolean isTemplateOnly(String body, String template) {
        if (template == null || template.isBlank()) {
            return false;
        }
        Set<String> templateLines = meaningfulLines(template);
        for (String line : meaningfulLines(body)) {
            if (!templateLines.contains(line)) {
                return false;
            }
        }
        return true;
    }

    /** Non-blank, non-boilerplate lines, normalized for comparison (comments and checkboxes dropped). */
    private static Set<String> meaningfulLines(String text) {
        Set<String> out = new LinkedHashSet<>();
        String noComments = text.replaceAll("(?s)<!--.*?-->", "\n");
        for (String rawLine : noComments.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || isBoilerplateLine(line)) {
                continue;
            }
            out.add(line.toLowerCase());
        }
        return out;
    }

    private static boolean isBoilerplateLine(String line) {
        String l = line.toLowerCase();
        return l.equals("- [ ]") || l.equals("- [x]") || l.equals("* [ ]") || l.equals("* [x]")
                || l.matches("^#{1,6}\\s*$") || l.equals("---") || l.equals("<!-- -->");
    }

    private static boolean hasDisclosure(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        for (String marker : DISCLOSURE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String resolve(Map<String, Object> metadata, String metaKey, String envKey) {
        Object v = metadata.get(metaKey);
        if (v != null) {
            return v.toString();
        }
        return env.apply(envKey);
    }

    private static int countWords(String title) {
        int count = 0;
        var m = WORD.matcher(title);
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static String stripTrailingPunctuation(String s) {
        return s.replaceAll("[\\p{Punct}\\s]+$", "");
    }

    private static void add(List<String> failures, List<Finding> findings, String ruleId, String message) {
        failures.add(message);
        findings.add(Finding.atLine(ruleId, "PR", 1, message));
    }

    private static int intConfig(AIVConfig.GateConfig cfg, String key, int fallback) {
        if (cfg == null) {
            return fallback;
        }
        Object v = cfg.getConfig().get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    private static boolean boolConfig(AIVConfig.GateConfig cfg, String key, boolean fallback) {
        if (cfg == null) {
            return fallback;
        }
        Object v = cfg.getConfig().get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return fallback;
    }
}
