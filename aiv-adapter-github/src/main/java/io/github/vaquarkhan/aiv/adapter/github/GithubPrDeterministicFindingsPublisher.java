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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes one deterministic AIV findings comment on a pull request.
 *
 * <p>The comment is deliberately evidence-oriented. It lists rules, paths, lines and fixed remediation
 * text, but it does not infer whether a human or an AI wrote the change. A repository may choose the
 * literal label {@code AI-SLOP}; that label means only that configured deterministic AIV rules found
 * evidence requiring review, not that AIV identified AI authorship.
 */
public final class GithubPrDeterministicFindingsPublisher {

    /** Stable marker used to find and update AIV's own aggregate comment. */
    public static final String MARKER = "<!-- aiv-deterministic-findings -->";
    /** Override base URL for tests, GitHub Enterprise, or an API proxy. */
    public static final String API_BASE_PROPERTY = "aiv.github.findings.api.base";
    private static final int MAX_FINDINGS = 40;
    private static final Pattern COMMENT_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");

    private GithubPrDeterministicFindingsPublisher() {
    }

    /** A finding paired with its gate ID and whether that gate blocks CI. */
    public record SelectedFinding(String gateId, boolean blocksCi, Finding finding) {
        public SelectedFinding {
            Objects.requireNonNull(gateId, "gateId");
            Objects.requireNonNull(finding, "finding");
        }
    }

    /**
     * Selects failed findings using optional gate and rule filters. Empty filters mean every failed
     * finding. A rule family ending {@code .*} matches its prefix, for example {@code security.*}.
     */
    public static List<SelectedFinding> select(AIVResult result, Collection<String> gateFilter,
                                                Collection<String> ruleFilter) {
        List<String> gates = normalize(gateFilter);
        List<String> rules = normalize(ruleFilter);
        List<SelectedFinding> selected = new ArrayList<>();
        if (result == null) {
            return selected;
        }
        for (GateResult gate : result.getGateResults()) {
            if (gate.isPassed() || !matches(gates, gate.getGateId())) {
                continue;
            }
            for (Finding finding : gate.getFindings()) {
                if (matches(rules, finding.getRuleId())) {
                    selected.add(new SelectedFinding(gate.getGateId(), gate.blocksCi(), finding));
                }
            }
        }
        selected.sort(Comparator.comparing(SelectedFinding::gateId)
                .thenComparing(s -> s.finding().getFilePath())
                .thenComparingInt(s -> s.finding().getStartLine())
                .thenComparing(s -> s.finding().getRuleId()));
        return selected;
    }

    /**
     * Creates or updates the marker comment when selected deterministic findings exist; removes the
     * marker comment when they no longer exist. Requires token, repository and PR number.
     */
    public static void publish(AIVResult result, Collection<String> gateFilter, Collection<String> ruleFilter,
                               Function<String, String> env) throws IOException, InterruptedException {
        String token = required(env, "GITHUB_TOKEN", "GITHUB_TOKEN is not set");
        String repo = required(env, "GITHUB_REPOSITORY", "GITHUB_REPOSITORY must be owner/repo");
        if (!repo.contains("/")) {
            throw new IllegalStateException("GITHUB_REPOSITORY must be owner/repo");
        }
        int prNumber = GithubPrLabelPublisher.resolvePrNumber(env);
        if (prNumber <= 0) {
            throw new IllegalStateException("Set AIV_GITHUB_PR_NUMBER or run on a pull_request event (GITHUB_EVENT_PATH)");
        }
        int slash = repo.indexOf('/');
        String owner = repo.substring(0, slash);
        String name = repo.substring(slash + 1);
        List<SelectedFinding> selected = select(result, gateFilter, ruleFilter);
        String base = apiBase(env);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        Long existing = findExistingCommentId(client, base, owner, name, prNumber, token);

        if (selected.isEmpty()) {
            if (existing != null) {
                deleteComment(client, base, owner, name, existing, token);
            }
            return;
        }
        String body = render(selected, result.getNotices());
        if (existing == null) {
            createComment(client, base, owner, name, prNumber, body, token);
        } else {
            updateComment(client, base, owner, name, existing, body, token);
        }
    }

    /**
     * Rendering plus the audit anchor: when the run appended to a hash-chained audit log, the comment repeats the
     * chain head. GitHub keeps comment edit history, so the head becomes an external anchor a later
     * {@code aiv audit verify --expect-head} can be checked against.
     */
    public static String render(List<SelectedFinding> selected, List<String> notices) {
        String body = render(selected);
        for (String n : notices) {
            if (n.startsWith("[AUDIT]")) {
                return body + "\n_Audit anchor:_ `" + n.substring("[AUDIT]".length()).trim() + "`\n";
            }
        }
        return body;
    }

    /** Stable Markdown rendering, deliberately free of AI-authorship claims and model output. */
    public static String render(List<SelectedFinding> selected) {
        StringBuilder out = new StringBuilder(MARKER).append("\n")
                .append("## AIV deterministic findings\n\n")
                .append("AIV found the configured evidence below. This comment does **not** determine whether AI authored the change. ")
                .append("Hard findings block CI; advisory findings require review according to repository policy.\n\n")
                .append("| Severity | Rule | Location | Evidence |\n")
                .append("|---|---|---|---|\n");
        int count = 0;
        for (SelectedFinding s : selected) {
            if (count++ == MAX_FINDINGS) {
                break;
            }
            Finding f = s.finding();
            String severity = s.blocksCi() ? "hard" : "advisory";
            String location = code(f.getFilePath() + ":" + f.getStartLine());
            String rule = code(f.getRuleId());
            out.append("| ").append(severity).append(" | ").append(rule).append(" | ")
                    .append(location).append(" | ").append(escapeCell(f.getMessage())).append(" |\n");
        }
        if (selected.size() > MAX_FINDINGS) {
            out.append("\nShowing the first ").append(MAX_FINDINGS).append(" of ").append(selected.size())
                    .append(" findings. Download AIV JSON or SARIF output for the complete result.\n");
        }
        out.append("\n**Next step:** fix the listed evidence, or apply an approved repository-specific exception with a reason. ")
                .append("Run `aiv explain <gate-or-rule-id>` for local help.\n");
        return out.toString();
    }

    private static Long findExistingCommentId(HttpClient client, String base, String owner, String name, int pr,
                                              String token) throws IOException, InterruptedException {
        URI uri = URI.create(base + "/repos/" + owner + "/" + name + "/issues/" + pr + "/comments?per_page=100");
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(uri).GET(), token);
        if (response.statusCode() != 200) {
            throw new IOException("GitHub issue comments GET returned HTTP " + response.statusCode() + ": " + response.body());
        }
        String json = response.body();
        int marker = json.indexOf(MARKER);
        if (marker < 0) {
            return null;
        }
        int objectStart = json.lastIndexOf('{', marker);
        if (objectStart < 0) {
            return null;
        }
        Matcher matcher = COMMENT_ID.matcher(json.substring(objectStart, marker));
        Long id = null;
        while (matcher.find()) {
            id = Long.parseLong(matcher.group(1));
        }
        return id;
    }

    private static void createComment(HttpClient client, String base, String owner, String name, int pr,
                                      String body, String token) throws IOException, InterruptedException {
        URI uri = URI.create(base + "/repos/" + owner + "/" + name + "/issues/" + pr + "/comments");
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString("{\"body\":\"" + escapeJson(body) + "\"}", StandardCharsets.UTF_8)), token);
        if (response.statusCode() != 201) {
            throw new IOException("GitHub issue comment create returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static void updateComment(HttpClient client, String base, String owner, String name, long id,
                                      String body, String token) throws IOException, InterruptedException {
        URI uri = URI.create(base + "/repos/" + owner + "/" + name + "/issues/comments/" + id);
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(uri)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"body\":\"" + escapeJson(body) + "\"}", StandardCharsets.UTF_8)), token);
        if (response.statusCode() != 200) {
            throw new IOException("GitHub issue comment update returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static void deleteComment(HttpClient client, String base, String owner, String name, long id,
                                      String token) throws IOException, InterruptedException {
        URI uri = URI.create(base + "/repos/" + owner + "/" + name + "/issues/comments/" + id);
        HttpResponse<String> response = send(client, HttpRequest.newBuilder(uri).DELETE(), token);
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new IOException("GitHub issue comment delete returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder builder, String token)
            throws IOException, InterruptedException {
        return client.send(builder.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static boolean matches(List<String> filter, String value) {
        if (filter.isEmpty()) {
            return true;
        }
        String candidate = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String configured : filter) {
            if (configured.endsWith(".*")) {
                if (candidate.startsWith(configured.substring(0, configured.length() - 1))) {
                    return true;
                }
            } else if (candidate.equals(configured)) {
                return true;
            }
        }
        return false;
    }

    static List<String> normalize(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
    }

    static String apiBase(Function<String, String> env) {
        String configured = System.getProperty(API_BASE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = env.apply("AIV_GITHUB_FINDINGS_API_BASE");
        }
        if (configured == null || configured.isBlank()) {
            return "https://api.github.com";
        }
        String trimmed = configured.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    static String escapeCell(String text) {
        return (text == null ? "" : text).replace("|", "\\|").replace("\n", "<br>").replace("\r", "");
    }

    static String code(String text) {
        return "`" + (text == null ? "" : text).replace("`", "'") + "`";
    }

    static String escapeJson(String text) {
        return (text == null ? "" : text).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String required(Function<String, String> env, String key, String message) {
        String value = env.apply(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }
}
