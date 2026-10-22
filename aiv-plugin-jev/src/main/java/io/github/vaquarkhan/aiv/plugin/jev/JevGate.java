/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.jev;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Advisory-only, opt-in gate that asks a System One decision model calibrated yes/no questions about the diff.
 *
 * <p>This is the one gate in AIV that calls a model. It is deliberately boxed in: it can never block
 * (every outcome is {@code pass} or {@code advisory}, whatever the configured severity), it runs only when a
 * project configures it, it sends only the diff and the PR text, and its answer is a probability that is
 * written into the receipt next to the deterministic findings so a reviewer can see exactly what the model was
 * asked and how sure it was. AIV does not use it to decide authorship.
 *
 * <pre>
 * - id: jev
 *   enabled: true
 *   config:
 *     base_url: https://api.typesafe.ai      # Ollama: http://localhost:11434 (same API, no key)
 *     api_key_env: TYPESAFE_API_KEY          # empty string when the host needs no key
 *     model: jev-latest
 *     report_at: 0.75                        # report a check when P(yes) is at or above this
 *     max_diff_chars: 60000
 *     timeout_seconds: 30
 *     checks:
 *       description-mismatch: "Does `diff` change behaviour that `pr_title` and `pr_body` do not mention?"
 *       tests-only-call:
 *         question: "Do the tests added in `diff` merely call the new code without checking its result?"
 *         yes: "At least one added test has no meaningful expectation about the outcome"
 *         no: "Every added test checks a result, an exception or a side effect"
 * </pre>
 *
 * Request: {@code POST <base_url>/v1/systemone} with {@code {"model", "state": {diff, changed_files, pr_title,
 * pr_body}, "questions": {<id>: {"type": "noul", "instructions", "criteria": {"true", "false"}}}}}. Response:
 * {@code answers.<id>.noul} is P(yes). Pricing and model behaviour are the host's; see docs.
 */
public final class JevGate implements QualityGate {

    public static final String ID = "jev";

    /** One HTTP exchange. */
    record Response(int status, String body) {
    }

    /** Seam for tests; production uses {@link HttpClient}. */
    @FunctionalInterface
    interface Transport {
        Response post(String url, Map<String, String> headers, String body, Duration timeout) throws IOException, InterruptedException;
    }

    record Check(String id, String question, String yes, String no) {
    }

    private final Transport transport;
    private final Function<String, String> env;

    public JevGate() {
        this(JevGate::httpPost, System::getenv);
    }

    JevGate(Transport transport, Function<String, String> env) {
        this.transport = transport;
        this.env = env;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Map<String, Object> cfg = context.getConfig().findGate(ID).map(g -> g.getConfig()).orElse(Map.of());
        List<Check> checks = parseChecks(cfg.get("checks"));
        if (checks.isEmpty()) {
            return new GateResult(ID, true, "No checks configured", false, List.of());
        }
        String baseUrl = str(cfg.get("base_url"), "https://api.typesafe.ai").replaceAll("/+$", "");
        // An explicit empty string means "this host needs no key" (local Ollama); absent means the TypeSafe default.
        String keyEnv = cfg.get("api_key_env") == null ? "TYPESAFE_API_KEY" : cfg.get("api_key_env").toString().trim();
        String model = str(cfg.get("model"), "jev-latest");
        double reportAt = cfg.get("report_at") instanceof Number n ? n.doubleValue() : 0.75;
        int maxChars = cfg.get("max_diff_chars") instanceof Number n ? n.intValue() : 60000;
        long timeout = cfg.get("timeout_seconds") instanceof Number n ? n.longValue() : 30L;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (!keyEnv.isBlank()) {
            String key = env.apply(keyEnv);
            if (key == null || key.isBlank()) {
                return skipped("environment variable " + keyEnv + " is not set; model check not run");
            }
            headers.put("Authorization", "Bearer " + key);
        }

        String body = JsonLite.write(request(context, env, checks, model, maxChars));
        Response resp;
        try {
            resp = transport.post(baseUrl + "/v1/systemone", headers, body, Duration.ofSeconds(timeout));
        } catch (IOException e) {
            return skipped("request failed (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return skipped("request interrupted");
        }
        if (resp.status() / 100 != 2) {
            return skipped("HTTP " + resp.status() + " from " + baseUrl + (resp.body() == null ? "" : ": " + head(resp.body())));
        }
        Object json;
        try {
            json = JsonLite.parse(resp.body());
        } catch (IllegalArgumentException e) {
            return skipped("response is not JSON (" + e.getMessage() + ")");
        }

        List<Finding> findings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        String usedModel = JsonLite.str(JsonLite.get(json, "model"));
        Object tokens = JsonLite.get(json, "usage", "input_tokens");
        lines.add("model " + (usedModel == null ? model : usedModel) + (tokens == null ? "" : ", input_tokens " + tokens)
                + ", report_at " + reportAt + " (advisory: a probability is evidence for a reviewer, never a block)");
        for (Check c : checks) {
            Object p = JsonLite.get(json, "answers", c.id(), "noul");
            if (!(p instanceof Number prob)) {
                lines.add(c.id() + ": no answer returned");
                continue;
            }
            String pct = String.format(Locale.ROOT, "%.2f", prob.doubleValue());
            lines.add(c.id() + ": P(yes)=" + pct);
            if (prob.doubleValue() >= reportAt) {
                findings.add(Finding.atLine(ID + "." + c.id(), "(diff)", 1,
                        "model P(yes)=" + pct + " for: " + c.question()));
            }
        }
        String summary = String.join("\n", lines);
        return findings.isEmpty()
                ? new GateResult(ID, true, summary, false, List.of())
                : GateResult.advisory(ID, summary, findings);
    }

    /** Metadata key first (set by the CLI), then the environment variable the composite action exports. */
    static String resolve(AIVContext context, Function<String, String> env, String key, String envVar) {
        Object v = context.getMetadata().get(key);
        if (v != null && !v.toString().isBlank()) {
            return v.toString();
        }
        String e = env.apply(envVar);
        return e == null ? "" : e;
    }

    static Map<String, Object> request(AIVContext context, Function<String, String> env, List<Check> checks,
                                       String model, int maxChars) {
        Map<String, Object> state = new LinkedHashMap<>();
        String diff = context.getDiff().getRawDiff();
        if (diff.length() > maxChars) {
            diff = diff.substring(0, maxChars) + "\n[... truncated by AIV at " + maxChars + " characters ...]";
        }
        state.put("diff", diff);
        List<String> files = new ArrayList<>();
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            files.add(f.getPath());
        }
        state.put("changed_files", files);
        state.put("pr_title", resolve(context, env, "pr_title", "AIV_PR_TITLE"));
        state.put("pr_body", resolve(context, env, "pr_body", "AIV_PR_BODY"));

        Map<String, Object> questions = new LinkedHashMap<>();
        for (Check c : checks) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("type", "noul");
            q.put("instructions", c.question());
            if (c.yes() != null || c.no() != null) {
                Map<String, Object> criteria = new LinkedHashMap<>();
                if (c.yes() != null) {
                    criteria.put("true", c.yes());
                }
                if (c.no() != null) {
                    criteria.put("false", c.no());
                }
                q.put("criteria", criteria);
            }
            questions.put(c.id(), q);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        req.put("state", state);
        req.put("questions", questions);
        return req;
    }

    static List<Check> parseChecks(Object raw) {
        List<Check> out = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> m)) {
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String id = String.valueOf(e.getKey()).trim();
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("jev.checks: id '" + id + "' must be alphanumeric with - or _");
            }
            Object v = e.getValue();
            if (v instanceof Map<?, ?> cm) {
                String q = JsonLite.str(cm.get("question"));
                if (q == null || q.isBlank()) {
                    throw new IllegalArgumentException("jev.checks." + id + ": needs 'question'");
                }
                out.add(new Check(id, q.trim(), JsonLite.str(cm.get("yes")), JsonLite.str(cm.get("no"))));
            } else if (v != null && !v.toString().isBlank()) {
                out.add(new Check(id, v.toString().trim(), null, null));
            } else {
                throw new IllegalArgumentException("jev.checks." + id + ": needs a question");
            }
        }
        if (out.size() > 64) {
            throw new IllegalArgumentException("jev.checks: at most 64 checks per run");
        }
        return out;
    }

    private static GateResult skipped(String why) {
        return new GateResult(ID, true, "SKIPPED jev: " + why, false, List.of());
    }

    private static String str(Object v, String dflt) {
        return v == null || v.toString().isBlank() ? dflt : v.toString().trim();
    }

    private static String head(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    static Response httpPost(String url, Map<String, String> headers, String body, Duration timeout)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("User-Agent", "aiv-integrity-gate")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }
}
