/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.github;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Posts one PR review comment per finding with a short deterministic fix hint (no LLM).
 * Requires {@code GITHUB_TOKEN}, {@code GITHUB_REPOSITORY}, commit SHA, and PR number.
 */
public final class GithubPrReviewCommentsPublisher {

    public static final String COMMENTS_API_BASE_PROPERTY = "aiv.github.pr.comments.api.base";
    private static final int MAX_COMMENTS = 30;

    private static final Map<String, String> HINTS = Map.ofEntries(
            Map.entry("invariant.merge-conflict",
                    "Remove the conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) and keep one resolved side."),
            Map.entry("invariant.ai-edit-artifact",
                    "Delete agent paste artifacts (elision like `// ... existing code ...`, SEARCH/REPLACE blocks, assistant chatter)."),
            Map.entry("invariant.placeholder",
                    "Replace TBD/FIXME/XXX with real code or remove the placeholder line."),
            Map.entry("syntax.parse",
                    "Fix the parse error on this line so the file compiles / parses cleanly."),
            Map.entry("security.aws-key", "Remove the hard-coded AWS key; use a secret store or CI secret."),
            Map.entry("security.github-token", "Remove the hard-coded GitHub token; use a secret store."),
            Map.entry("security.slack-token", "Remove the hard-coded Slack token; use a secret store."),
            Map.entry("security.private-key", "Remove the private key material from the diff."),
            Map.entry("security.stripe-live", "Remove the Stripe live secret; use a secret store."),
            Map.entry("security.npm-token", "Remove the npm token; use a secret store."),
            Map.entry("security.google-api-key", "Remove the Google API key; use a secret store."),
            Map.entry("security.assigned-secret", "Remove the hard-coded secret assignment; inject at runtime.")
    );

    private GithubPrReviewCommentsPublisher() {
    }

    public static void publish(AIVResult result, Function<String, String> env)
            throws IOException, InterruptedException {
        if (result == null || result.isPassed()) {
            return;
        }
        String token = env.apply("GITHUB_TOKEN");
        String repo = env.apply("GITHUB_REPOSITORY");
        String sha = firstNonBlank(env.apply("AIV_GITHUB_HEAD_SHA"), env.apply("GITHUB_SHA"));
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("GITHUB_TOKEN is not set");
        }
        if (repo == null || !repo.contains("/")) {
            throw new IllegalStateException("GITHUB_REPOSITORY must be owner/repo");
        }
        if (sha == null || sha.isBlank()) {
            throw new IllegalStateException("Set GITHUB_SHA or AIV_GITHUB_HEAD_SHA");
        }
        int pr = GithubPrLabelPublisher.resolvePrNumber(env);
        if (pr <= 0) {
            throw new IllegalStateException(
                    "Set AIV_GITHUB_PR_NUMBER or run on a pull_request event (GITHUB_EVENT_PATH)");
        }
        int slash = repo.indexOf('/');
        String owner = repo.substring(0, slash);
        String name = repo.substring(slash + 1);

        List<Finding> findings = new ArrayList<>();
        for (GateResult g : result.getGateResults()) {
            if (g.isPassed()) {
                continue;
            }
            findings.addAll(g.getFindings());
        }
        if (findings.isEmpty()) {
            return;
        }

        String base = apiBase(env);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        int posted = 0;
        for (Finding f : findings) {
            if (posted >= MAX_COMMENTS) {
                break;
            }
            if (f.getFilePath() == null || f.getFilePath().isBlank()) {
                continue;
            }
            String body = buildBody(f);
            String json = "{"
                    + "\"body\":\"" + escapeJson(body) + "\","
                    + "\"commit_id\":\"" + escapeJson(sha) + "\","
                    + "\"path\":\"" + escapeJson(f.getFilePath().replace('\\', '/')) + "\","
                    + "\"line\":" + Math.max(1, f.getStartLine()) + ","
                    + "\"side\":\"RIGHT\""
                    + "}";
            URI uri = URI.create(base + "/repos/" + owner + "/" + name + "/pulls/" + pr + "/comments");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                throw new IOException("GitHub PR comments API returned HTTP " + code + ": " + response.body());
            }
            posted++;
        }
    }

    static String buildBody(Finding f) {
        String hint = hintFor(f.getRuleId());
        return "**AIV** `" + f.getRuleId() + "`\n\n"
                + f.getMessage() + "\n\n"
                + "**Fix:** " + hint;
    }

    static String hintFor(String ruleId) {
        if (ruleId == null) {
            return "Address the finding on this line.";
        }
        String exact = HINTS.get(ruleId);
        if (exact != null) {
            return exact;
        }
        String lower = ruleId.toLowerCase(Locale.ROOT);
        for (var e : HINTS.entrySet()) {
            if (lower.startsWith(e.getKey().toLowerCase(Locale.ROOT).replace(".*", ""))) {
                return e.getValue();
            }
        }
        if (lower.startsWith("security.")) {
            return "Remove the credential from the diff; load secrets from the environment or a vault.";
        }
        if (lower.startsWith("invariant.")) {
            return "Remove the junk / placeholder / conflict artifact from the added lines.";
        }
        return "Address the finding on this line (see `aiv explain` for the gate).";
    }

    static String apiBase(Function<String, String> env) {
        String override = System.getProperty(COMMENTS_API_BASE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = env.apply("AIV_GITHUB_PR_COMMENTS_API_BASE");
        }
        if (override != null && !override.isBlank()) {
            String b = override.trim();
            return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        }
        return "https://api.github.com";
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
