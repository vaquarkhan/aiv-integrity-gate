/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.github;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Publishes the {@code review-routing} tier as exactly one PR label ({@code aiv:review-<tier>} by default),
 * removing any other label with the same prefix. AI review automation (for example the Copilot review
 * workflow) reads that label to decide whether and how deeply to ask a model.
 *
 * @author Vaquar Khan
 */
public final class GithubReviewTierLabelPublisher {

    public static final String DEFAULT_PREFIX = "aiv:review-";
    static final String GATE_ID = "review-routing";
    static final String RULE_ID = "review-routing.tier";
    private static final Pattern TIER = Pattern.compile("[a-z0-9][a-z0-9-]{0,29}");

    private GithubReviewTierLabelPublisher() {
    }

    /** Tier reported by the review-routing gate, or empty when the gate did not run. */
    public static String tier(AIVResult result) {
        if (result == null) {
            return "";
        }
        for (GateResult g : result.getGateResults()) {
            if (!GATE_ID.equals(g.getGateId())) {
                continue;
            }
            for (Finding f : g.getFindings()) {
                String m = f.getMessage();
                if (RULE_ID.equals(f.getRuleId()) && m != null && m.startsWith("tier=")) {
                    int end = m.indexOf(';');
                    String t = (end < 0 ? m.substring(5) : m.substring(5, end)).trim();
                    if (TIER.matcher(t).matches()) {
                        return t;
                    }
                }
            }
        }
        return "";
    }

    /**
     * Sets the tier label on the current PR. Requires {@code GITHUB_TOKEN}, {@code GITHUB_REPOSITORY} and a PR
     * number ({@code AIV_GITHUB_PR_NUMBER} or the event payload). Throws when the gate produced no tier, so a
     * workflow that asked for the label never silently runs without routing.
     *
     * @return the label that was applied
     */
    public static String publish(AIVResult result, String prefix, Function<String, String> env)
            throws IOException, InterruptedException {
        String tier = tier(result);
        if (tier.isEmpty()) {
            throw new IllegalStateException("--label-review-tier needs the review-routing gate enabled in .aiv/config.yaml");
        }
        String token = env.apply("GITHUB_TOKEN");
        String repo = env.apply("GITHUB_REPOSITORY");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("GITHUB_TOKEN is not set");
        }
        if (repo == null || !repo.contains("/")) {
            throw new IllegalStateException("GITHUB_REPOSITORY must be owner/repo");
        }
        int pr = GithubPrLabelPublisher.resolvePrNumber(env);
        if (pr <= 0) {
            throw new IllegalStateException("Set AIV_GITHUB_PR_NUMBER or run on a pull_request event (GITHUB_EVENT_PATH)");
        }
        String p = prefix == null || prefix.isBlank() ? DEFAULT_PREFIX : prefix.trim();
        String label = p + tier;
        String owner = repo.substring(0, repo.indexOf('/'));
        String name = repo.substring(repo.indexOf('/') + 1);

        List<String> current = currentLabels(owner, name, pr, token, env);
        for (String existing : current) {
            if (existing.startsWith(p) && !existing.equals(label)) {
                GithubPrLabelPublisher.removeLabel(owner, name, pr, existing, token, env);
            }
        }
        if (!current.contains(label)) {
            GithubPrLabelPublisher.ensureLabelExists(owner, name, label,
                    "AIV review routing tier (deterministic, advisory)", "C5DEF5", token, env);
            GithubPrLabelPublisher.addLabel(owner, name, pr, label, token, env);
        }
        return label;
    }

    static List<String> currentLabels(String owner, String name, int pr, String token, Function<String, String> env)
            throws IOException, InterruptedException {
        URI uri = URI.create(GithubPrLabelPublisher.apiBase(env) + "/repos/" + owner + "/" + name + "/issues/" + pr
                + "/labels?per_page=100");
        HttpResponse<String> r = GithubPrLabelPublisher.send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build());
        if (r.statusCode() != 200) {
            throw new IOException("GitHub list labels returned HTTP " + r.statusCode() + ": " + r.body());
        }
        List<String> out = new ArrayList<>();
        for (Object o : JsonLite.list(JsonLite.parse(r.body()))) {
            String n = JsonLite.str(JsonLite.get(o, "name"));
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }
}
