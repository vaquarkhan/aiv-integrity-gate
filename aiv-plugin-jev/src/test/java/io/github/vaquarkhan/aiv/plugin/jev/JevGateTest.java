/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.jev;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.util.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevGateTest {

    private static final Map<String, Object> CHECKS = Map.of(
            "mismatch", "Does `diff` do more than `pr_title` says?",
            "hollow", Map.of("question", "Are the added tests hollow?", "yes", "no expectation", "no", "checks a result"));

    private static AIVContext ctx(Map<String, Object> cfg, String diff, Map<String, Object> metadata) {
        Map<String, Object> full = new HashMap<>(cfg);
        full.putIfAbsent("checks", CHECKS);
        AIVConfig config = new AIVConfig(List.of(new AIVConfig.GateConfig(JevGate.ID, true, full)), Map.of());
        return new AIVContext(Path.of("."), new Diff("main", "HEAD",
                List.of(new ChangedFile("a.py", ChangedFile.ChangeType.MODIFIED, "x")), diff), config, metadata);
    }

    private static JevGate gate(JevGate.Transport t, Map<String, String> env) {
        return new JevGate(t, env::get);
    }

    @Test
    void registeredAndOffWithoutChecks() {
        assertTrue(ServiceLoader.load(QualityGate.class).stream().anyMatch(p -> p.type() == JevGate.class));
        assertEquals("jev", new JevGate().getId());
        AIVConfig none = new AIVConfig(List.of(), Map.of());
        GateResult r = new JevGate().evaluate(new AIVContext(Path.of("."), new Diff("m", "h", List.of(), ""), none));
        assertTrue(r.isPassed());
        assertEquals("No checks configured", r.getMessage());
    }

    @Test
    void reportsProbabilitiesAsAdvisoryNeverBlocking() {
        AtomicReference<String> sentUrl = new AtomicReference<>();
        AtomicReference<String> sentBody = new AtomicReference<>();
        AtomicReference<Map<String, String>> sentHeaders = new AtomicReference<>();
        JevGate.Transport t = (url, headers, body, timeout) -> {
            sentUrl.set(url);
            sentBody.set(body);
            sentHeaders.set(headers);
            return new JevGate.Response(200, "{\"model\":\"jev-1.13\",\"answers\":{\"mismatch\":{\"type\":\"noul\",\"noul\":0.91},"
                    + "\"hollow\":{\"type\":\"noul\",\"noul\":0.12}},\"usage\":{\"input_tokens\":410,\"output_tokens\":2}}");
        };
        Map<String, Object> cfg = Map.of("base_url", "https://api.typesafe.ai/", "max_diff_chars", 10, "report_at", 0.8);
        GateResult r = gate(t, Map.of("TYPESAFE_API_KEY", "k", "AIV_PR_BODY", "env body"))
                .evaluate(ctx(cfg, "0123456789ABCDEF", Map.of("pr_title", "Add retry")));
        assertFalse(r.isPassed());
        assertFalse(r.blocksCi(), "the model gate is advisory by construction");
        assertEquals(1, r.getFindings().size());
        assertEquals("jev.mismatch", r.getFindings().get(0).getRuleId());
        assertTrue(r.getFindings().get(0).getMessage().contains("P(yes)=0.91"));
        assertTrue(r.getMessage().contains("model jev-1.13, input_tokens 410"));
        assertTrue(r.getMessage().contains("hollow: P(yes)=0.12"));

        assertEquals("https://api.typesafe.ai/v1/systemone", sentUrl.get());
        assertEquals("Bearer k", sentHeaders.get().get("Authorization"));
        Object req = JsonLite.parse(sentBody.get());
        assertEquals("jev-latest", JsonLite.get(req, "model"));
        assertEquals("0123456789\n[... truncated by AIV at 10 characters ...]", JsonLite.get(req, "state", "diff"));
        assertEquals("Add retry", JsonLite.get(req, "state", "pr_title"));
        assertEquals("env body", JsonLite.get(req, "state", "pr_body"));
        assertEquals(List.of("a.py"), JsonLite.get(req, "state", "changed_files"));
        assertEquals("noul", JsonLite.get(req, "questions", "mismatch", "type"));
        assertNull(JsonLite.get(req, "questions", "mismatch", "criteria"));
        assertEquals("no expectation", JsonLite.get(req, "questions", "hollow", "criteria", "true"));
        assertEquals("checks a result", JsonLite.get(req, "questions", "hollow", "criteria", "false"));
    }

    @Test
    void passesWhenNothingCrossesThresholdAndHandlesMissingAnswers() {
        JevGate.Transport t = (u, h, b, d) -> new JevGate.Response(200, "{\"answers\":{\"mismatch\":{\"noul\":0.2}}}");
        GateResult r = gate(t, Map.of()).evaluate(ctx(Map.of("api_key_env", ""), "d", Map.of()));
        assertTrue(r.isPassed());
        assertTrue(r.getMessage().contains("model jev-latest, report_at 0.75"));
        assertTrue(r.getMessage().contains("hollow: no answer returned"));
        assertTrue(r.getMessage().contains("mismatch: P(yes)=0.20"));
    }

    @Test
    void everyFailureModeIsReportedNotHidden() {
        Map<String, Object> cfg = Map.of();
        assertSkipped(gate((u, h, b, d) -> new JevGate.Response(200, "{}"), Map.of()).evaluate(ctx(cfg, "d", Map.of())),
                "TYPESAFE_API_KEY is not set");
        Map<String, String> env = Map.of("TYPESAFE_API_KEY", "k");
        assertSkipped(gate((u, h, b, d) -> { throw new IOException("refused"); }, env).evaluate(ctx(cfg, "d", Map.of())), "refused");
        assertSkipped(gate((u, h, b, d) -> { throw new InterruptedException(); }, env).evaluate(ctx(cfg, "d", Map.of())), "interrupted");
        assertTrue(Thread.interrupted());
        assertSkipped(gate((u, h, b, d) -> new JevGate.Response(401, " {\"error\":  \"bad key\"} "), env).evaluate(ctx(cfg, "d", Map.of())),
                "HTTP 401 from https://api.typesafe.ai: {\"error\": \"bad key\"}");
        assertSkipped(gate((u, h, b, d) -> new JevGate.Response(500, null), env).evaluate(ctx(cfg, "d", Map.of())), "HTTP 500");
        assertSkipped(gate((u, h, b, d) -> new JevGate.Response(502, "x".repeat(300)), env).evaluate(ctx(cfg, "d", Map.of())), "...");
        assertSkipped(gate((u, h, b, d) -> new JevGate.Response(200, "<html>"), env).evaluate(ctx(cfg, "d", Map.of())), "not JSON");
    }

    private static void assertSkipped(GateResult r, String fragment) {
        assertTrue(r.isPassed());
        assertFalse(r.blocksCi());
        assertTrue(r.getMessage().startsWith("SKIPPED jev: "), r.getMessage());
        assertTrue(r.getMessage().contains(fragment), r.getMessage());
    }

    @Test
    void checkParsingValidation() {
        assertTrue(JevGate.parseChecks(null).isEmpty());
        assertTrue(JevGate.parseChecks("string").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> JevGate.parseChecks(Map.of("bad id!", "q")));
        assertThrows(IllegalArgumentException.class, () -> JevGate.parseChecks(Map.of("a", Map.of("yes", "y"))));
        assertThrows(IllegalArgumentException.class, () -> JevGate.parseChecks(Map.of("a", " ")));
        Map<String, Object> m = new HashMap<>();
        m.put("a", null);
        assertThrows(IllegalArgumentException.class, () -> JevGate.parseChecks(m));
        Map<String, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) {
            many.put("c" + i, "q");
        }
        assertThrows(IllegalArgumentException.class, () -> JevGate.parseChecks(many));
        List<JevGate.Check> ok = JevGate.parseChecks(Map.of("a", Map.of("question", " q ")));
        assertEquals("q", ok.get(0).question());
        assertNull(ok.get(0).yes());
        Map<String, Object> onlyNo = JevGate.request(ctx(Map.of(), "", Map.of()), k -> null,
                List.of(new JevGate.Check("x", "q", null, "n")), "m", 10);
        assertEquals("n", JsonLite.get(onlyNo, "questions", "x", "criteria", "false"));
        assertEquals("", JsonLite.get(onlyNo, "state", "pr_title"));
    }

    @Test
    void realHttpTransportRoundTrips() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/v1/systemone", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] in = ex.getRequestBody().readAllBytes();
            byte[] out = ("{\"answers\":{\"mismatch\":{\"noul\":0.99}},\"echo\":" + in.length + "}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GateResult r = new JevGate(JevGate::httpPost, Map.of("TYPESAFE_API_KEY", "secret")::get)
                    .evaluate(ctx(Map.of("base_url", base, "timeout_seconds", 5), "diff text", Map.of()));
            assertFalse(r.isPassed());
            assertFalse(r.blocksCi());
            assertEquals("Bearer secret", auth.get());
            JevGate.Response raw = JevGate.httpPost(base + "/v1/systemone", Map.of(), "{}", Duration.ofSeconds(5));
            assertEquals(200, raw.status());
            assertTrue(raw.body().contains("\"echo\":2"));
        } finally {
            server.stop(0);
        }
        assertThrows(IOException.class, () -> JevGate.httpPost("http://127.0.0.1:1/v1/systemone", Map.of(), "{}", Duration.ofSeconds(2)));
    }
}
