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
import java.util.function.Function;

/**
 * Creates a single GitHub Check run with inline annotations from structured {@link Finding}s.
 * Requires {@code GITHUB_TOKEN}, {@code GITHUB_REPOSITORY} ({@code owner/repo}), and
 * {@code GITHUB_SHA} (or override {@code AIV_GITHUB_HEAD_SHA} for PR head commits).
 */
public final class GithubChecksPublisher {

    private static final int MAX_ANNOTATIONS = 50;

    /**
     * When set (e.g. in tests), POST target for check-runs; otherwise derived from {@code GITHUB_REPOSITORY}.
     */
    public static final String CHECKS_URL_PROPERTY = "aiv.github.checks.url";

    private GithubChecksPublisher() {
    }

    /**
     * @param env maps variable names (e.g. {@code GITHUB_TOKEN}) to values; production callers use {@code System::getenv}.
     */
    public static void publish(AIVResult result, String cliVersion, Function<String, String> env)
            throws IOException, InterruptedException {
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
            throw new IllegalStateException("Set GITHUB_SHA or AIV_GITHUB_HEAD_SHA to the commit under test");
        }
        int slash = repo.indexOf('/');
        String owner = repo.substring(0, slash);
        String name = repo.substring(slash + 1);

        String conclusion = result.isPassed() ? "success" : "failure";
        String summary = buildSummary(result, cliVersion);
        String annotationsJson = buildAnnotationsJson(result);

        String body = "{"
                + "\"name\":\"AIV Integrity Gate\","
                + "\"head_sha\":\"" + escapeJson(sha) + "\","
                + "\"status\":\"completed\","
                + "\"conclusion\":\"" + conclusion + "\","
                + "\"output\":{"
                + "\"title\":\"AIV " + (result.isPassed() ? "passed" : "failed") + "\","
                + "\"summary\":\"" + escapeJson(summary) + "\","
                + "\"annotations\":" + annotationsJson
                + "}"
                + "}";

        URI uri = resolveChecksUri(owner, name, env);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub Checks API returned HTTP " + code + ": " + response.body());
        }
    }

    static URI resolveChecksUri(String owner, String name, Function<String, String> env) {
        String override = System.getProperty(CHECKS_URL_PROPERTY);
        if (override == null || override.isBlank()) {
            override = env.apply("AIV_GITHUB_CHECKS_URL");
        }
        if (override != null && !override.isBlank()) {
            return URI.create(override.trim());
        }
        return URI.create("https://api.github.com/repos/" + owner + "/" + name + "/check-runs");
    }

    private static String buildSummary(AIVResult result, String cliVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("aiv-cli ").append(cliVersion).append("\n\n");
        for (GateResult g : result.getGateResults()) {
            sb.append("**").append(g.getGateId()).append("** - ")
                    .append(g.isPassed() ? "PASS" : (g.blocksCi() ? "FAIL" : "ADVISORY")).append("\n");
            if (!g.getMessage().isEmpty()) {
                sb.append("\n").append(g.getMessage()).append("\n\n");
            }
        }
        for (String n : result.getNotices()) {
            sb.append("\n_Warning:_ ").append(n);
        }
        return sb.toString();
    }

    private static String buildAnnotationsJson(AIVResult result) {
        List<Annotation> list = new ArrayList<>();
        for (GateResult g : result.getGateResults()) {
            if (g.isPassed()) {
                continue;
            }
            for (Finding f : g.getFindings()) {
                if (list.size() >= MAX_ANNOTATIONS) {
                    break;
                }
                list.add(new Annotation(
                        f.getFilePath().replace('\\', '/'),
                        f.getStartLine(),
                        f.getMessage(),
                        g.blocksCi() ? "failure" : "warning"));
            }
        }
        if (list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            Annotation a = list.get(i);
            sb.append("{")
                    .append("\"path\":\"").append(escapeJson(a.path)).append("\",")
                    .append("\"start_line\":").append(a.startLine).append(",")
                    .append("\"end_line\":").append(a.startLine).append(",")
                    .append("\"annotation_level\":\"").append(a.level).append("\",")
                    .append("\"message\":\"").append(escapeJson(a.message)).append("\"")
                    .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private record Annotation(String path, int startLine, String message, String level) {
    }

    static String firstNonBlank(String a, String b) {
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
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
