/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.github;

import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.GateResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies or removes a GitHub PR label when soft (non-blocking) AI-slop signals are found.
 * Use when gate {@code severity: warn} reports findings but must not fail CI.
 *
 * <p>Requires {@code GITHUB_TOKEN}, {@code GITHUB_REPOSITORY} ({@code owner/repo}), and
 * {@code GITHUB_EVENT_PATH} (PR) or {@code AIV_GITHUB_PR_NUMBER}.
 */
public final class GithubPrLabelPublisher {

    public static final String DEFAULT_LABEL = "aiv:advisory";
    public static final List<String> DEFAULT_AI_SLOP_GATES = List.of("design", "invariant", "density", "cohesion");

    /** When set (e.g. in tests), base API URL for labels; otherwise {@code https://api.github.com}. */
    public static final String LABELS_API_BASE_PROPERTY = "aiv.github.labels.api.base";

    private GithubPrLabelPublisher() {
    }

    /**
     * If the result has advisory findings from configured AI-slop gates, add {@code label};
     * otherwise remove {@code label} when present (best-effort).
     *
     * @param env maps variable names to values; production callers use {@code System::getenv}.
     */
    public static void apply(AIVResult result, String label, Collection<String> aiSlopGates, Function<String, String> env)
            throws IOException, InterruptedException {
        String token = env.apply("GITHUB_TOKEN");
        String repo = env.apply("GITHUB_REPOSITORY");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("GITHUB_TOKEN is not set");
        }
        if (repo == null || !repo.contains("/")) {
            throw new IllegalStateException("GITHUB_REPOSITORY must be owner/repo");
        }
        String resolvedLabel = (label == null || label.isBlank()) ? DEFAULT_LABEL : label.trim();
        int prNumber = resolvePrNumber(env);
        if (prNumber <= 0) {
            throw new IllegalStateException(
                    "Set AIV_GITHUB_PR_NUMBER or run on a pull_request event (GITHUB_EVENT_PATH)");
        }

        int slash = repo.indexOf('/');
        String owner = repo.substring(0, slash);
        String name = repo.substring(slash + 1);
        Set<String> gates = normalizeGates(aiSlopGates);
        boolean shouldLabel = hasAdvisoryAiSlop(result, gates);

        ensureLabelExists(owner, name, resolvedLabel, token, env);
        if (shouldLabel) {
            addLabel(owner, name, prNumber, resolvedLabel, token, env);
        } else {
            removeLabel(owner, name, prNumber, resolvedLabel, token, env);
        }
    }

    static boolean hasAdvisoryAiSlop(AIVResult result, Set<String> aiSlopGates) {
        if (result == null) {
            return false;
        }
        for (GateResult g : result.getGateResults()) {
            if (g.isPassed() || g.blocksCi()) {
                continue;
            }
            if (aiSlopGates.isEmpty() || aiSlopGates.contains(g.getGateId().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static Set<String> normalizeGates(Collection<String> aiSlopGates) {
        if (aiSlopGates == null || aiSlopGates.isEmpty()) {
            return DEFAULT_AI_SLOP_GATES.stream()
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
        return aiSlopGates.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    static int resolvePrNumber(Function<String, String> env) throws IOException {
        String override = env.apply("AIV_GITHUB_PR_NUMBER");
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("AIV_GITHUB_PR_NUMBER must be an integer", e);
            }
        }
        String eventPath = env.apply("GITHUB_EVENT_PATH");
        if (eventPath == null || eventPath.isBlank()) {
            return -1;
        }
        String json = java.nio.file.Files.readString(java.nio.file.Path.of(eventPath));
        return parsePullRequestNumber(json);
    }

    static int parsePullRequestNumber(String eventJson) {
        if (eventJson == null || eventJson.isBlank()) {
            return -1;
        }
        // Prefer pull_request.number; fall back to issue.number for issue_comment on PRs.
        int fromPr = extractJsonIntAfter(eventJson, "\"pull_request\"");
        if (fromPr > 0) {
            return fromPr;
        }
        return extractJsonIntAfter(eventJson, "\"issue\"");
    }

    private static int extractJsonIntAfter(String json, String objectKey) {
        int obj = json.indexOf(objectKey);
        if (obj < 0) {
            return -1;
        }
        int numberKey = json.indexOf("\"number\"", obj);
        if (numberKey < 0 || numberKey - obj > 800) {
            return -1;
        }
        int colon = json.indexOf(':', numberKey);
        if (colon < 0) {
            return -1;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) {
            i++;
        }
        if (start == i) {
            return -1;
        }
        try {
            return Integer.parseInt(json.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void ensureLabelExists(String owner, String name, String label, String token,
            Function<String, String> env) throws IOException, InterruptedException {
        URI getUri = URI.create(apiBase(env) + "/repos/" + owner + "/" + name + "/labels/"
                + URLEncoder.encode(label, StandardCharsets.UTF_8).replace("+", "%20"));
        HttpResponse<String> get = send(HttpRequest.newBuilder(getUri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build());
        if (get.statusCode() == 200) {
            return;
        }
        if (get.statusCode() != 404) {
            throw new IOException("GitHub Labels GET returned HTTP " + get.statusCode() + ": " + get.body());
        }
        String body = "{"
                + "\"name\":\"" + GithubChecksPublisher.escapeJson(label) + "\","
                + "\"color\":\"FBCA04\","
                + "\"description\":\"AIV found advisory AI-slop signals (non-blocking)\""
                + "}";
        URI postUri = URI.create(apiBase(env) + "/repos/" + owner + "/" + name + "/labels");
        HttpResponse<String> post = send(HttpRequest.newBuilder(postUri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
        int code = post.statusCode();
        // 201 created; 422 often means label already exists (race)
        if (code != 201 && code != 422) {
            throw new IOException("GitHub Labels create returned HTTP " + code + ": " + post.body());
        }
    }

    private static void addLabel(String owner, String name, int prNumber, String label, String token,
            Function<String, String> env) throws IOException, InterruptedException {
        String body = "{\"labels\":[\"" + GithubChecksPublisher.escapeJson(label) + "\"]}";
        URI uri = URI.create(apiBase(env) + "/repos/" + owner + "/" + name + "/issues/" + prNumber + "/labels");
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub add label returned HTTP " + code + ": " + response.body());
        }
    }

    private static void removeLabel(String owner, String name, int prNumber, String label, String token,
            Function<String, String> env) throws IOException, InterruptedException {
        URI uri = URI.create(apiBase(env) + "/repos/" + owner + "/" + name + "/issues/" + prNumber + "/labels/"
                + URLEncoder.encode(label, StandardCharsets.UTF_8).replace("+", "%20"));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .DELETE()
                .build());
        int code = response.statusCode();
        // 204 removed; 404 already absent — both OK
        if (code != 204 && code != 404 && (code < 200 || code >= 300)) {
            throw new IOException("GitHub remove label returned HTTP " + code + ": " + response.body());
        }
    }

    static String apiBase(Function<String, String> env) {
        String prop = System.getProperty(LABELS_API_BASE_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return trimTrailingSlash(prop.trim());
        }
        String fromEnv = env.apply("AIV_GITHUB_API_BASE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return trimTrailingSlash(fromEnv.trim());
        }
        return "https://api.github.com";
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
