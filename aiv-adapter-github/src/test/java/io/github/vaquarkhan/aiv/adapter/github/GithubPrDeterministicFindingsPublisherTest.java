/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.adapter.github;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aiv.model.AIVResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubPrDeterministicFindingsPublisherTest {

    @AfterEach
    void clear() {
        System.clearProperty(GithubPrDeterministicFindingsPublisher.API_BASE_PROPERTY);
    }

    private static AIVResult sample() {
        return new AIVResult(false, List.of(
                GateResult.pass("syntax"),
                GateResult.fail("invariant", "m", List.of(
                        Finding.atLine("invariant.merge-conflict", "b.py", 3, "conflict | marker\nsecond"),
                        Finding.atLine("invariant.placeholder", "a.py", 1, "placeholder"))),
                GateResult.advisory("duplication", "d", List.of(Finding.atLine("duplication.clone", "c.py", 9, "clone"))),
                GateResult.fail("security", "s", List.of(Finding.atLine("security.aws-key", "k.env", 2, "key")))));
    }

    @Test
    void selectAppliesGateAndRuleFiltersAndSortsDeterministically() {
        var all = GithubPrDeterministicFindingsPublisher.select(sample(), null, null);
        assertEquals(4, all.size());
        assertEquals("duplication", all.get(0).gateId());
        assertFalse(all.get(0).blocksCi());
        assertEquals("a.py", all.get(1).finding().getFilePath());
        assertEquals("b.py", all.get(2).finding().getFilePath());

        var onlySecurity = GithubPrDeterministicFindingsPublisher.select(sample(), List.of(" Security "), List.of());
        assertEquals(1, onlySecurity.size());
        assertTrue(onlySecurity.get(0).blocksCi());

        var family = GithubPrDeterministicFindingsPublisher.select(sample(), List.of(), List.of("invariant.*"));
        assertEquals(2, family.size());

        var exact = GithubPrDeterministicFindingsPublisher.select(sample(), List.of(), List.of("duplication.clone"));
        assertEquals(1, exact.size());

        assertTrue(GithubPrDeterministicFindingsPublisher.select(null, null, null).isEmpty());
        assertTrue(GithubPrDeterministicFindingsPublisher.select(sample(), List.of("nope"), null).isEmpty());
        assertTrue(GithubPrDeterministicFindingsPublisher.matches(List.of(), null));
        assertFalse(GithubPrDeterministicFindingsPublisher.matches(List.of("x"), null));
        assertTrue(GithubPrDeterministicFindingsPublisher.normalize(null).isEmpty());
        assertThrows(NullPointerException.class,
                () -> new GithubPrDeterministicFindingsPublisher.SelectedFinding(null, false, Finding.atLine("r", "f", 1, "m")));
    }

    @Test
    void renderIsEvidenceOnlyAndEscapesTableCells() {
        String body = GithubPrDeterministicFindingsPublisher.render(
                GithubPrDeterministicFindingsPublisher.select(sample(), null, null));
        assertTrue(body.startsWith(GithubPrDeterministicFindingsPublisher.MARKER));
        assertTrue(body.contains("does **not** determine whether AI authored"));
        assertTrue(body.contains("| hard | `invariant.merge-conflict` | `b.py:3` | conflict \\| marker<br>second |"));
        assertTrue(body.contains("| advisory | `duplication.clone` |"));
        assertFalse(body.contains("Showing the first"));

        List<GithubPrDeterministicFindingsPublisher.SelectedFinding> many = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            many.add(new GithubPrDeterministicFindingsPublisher.SelectedFinding("g", true,
                    Finding.atLine("g.r", "f`" + i, i + 1, "m")));
        }
        String anchored = GithubPrDeterministicFindingsPublisher.render(many,
                List.of("[CONFIG-CHANGE] x", "[AUDIT] record #3 appended to a.log; chain head abc"));
        assertTrue(anchored.endsWith("\n_Audit anchor:_ `record #3 appended to a.log; chain head abc`\n"), anchored);
        assertEquals(GithubPrDeterministicFindingsPublisher.render(many),
                GithubPrDeterministicFindingsPublisher.render(many, List.of("[CONFIG-CHANGE] x")));
        String big = GithubPrDeterministicFindingsPublisher.render(many);
        assertTrue(big.contains("Showing the first 40 of 45 findings"));
        assertTrue(big.contains("`f'0:1`"));
        assertEquals("", GithubPrDeterministicFindingsPublisher.escapeCell(null));
        assertEquals("``", GithubPrDeterministicFindingsPublisher.code(null));
        assertEquals("a\\\\b\\\"\\n\\r\\t", GithubPrDeterministicFindingsPublisher.escapeJson("a\\b\"\n\r\t"));
        assertEquals("", GithubPrDeterministicFindingsPublisher.escapeJson(null));
    }

    @Test
    void publishCreatesUpdatesAndDeletesMarkerComment() throws Exception {
        List<String> calls = new ArrayList<>();
        String[] listing = {"[]"};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(key + (body.isEmpty() ? "" : " " + body.substring(0, Math.min(40, body.length()))));
            int code;
            String resp = "{}";
            if (key.startsWith("GET ")) {
                code = 200;
                resp = listing[0];
            } else if (key.startsWith("POST ")) {
                code = 201;
            } else if (key.startsWith("PATCH ")) {
                code = 200;
            } else {
                code = 204;
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, code == 204 ? -1 : out.length);
            if (code != 204) {
                ex.getResponseBody().write(out);
            }
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Function<String, String> env = envWith(Map.of("AIV_GITHUB_FINDINGS_API_BASE", base + "/"));
            assertEquals(base, GithubPrDeterministicFindingsPublisher.apiBase(env));

            // 1) no existing comment, findings present -> create
            GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, env);
            assertTrue(calls.get(0).startsWith("GET /repos/o/r/issues/12/comments"));
            assertTrue(calls.get(1).startsWith("POST /repos/o/r/issues/12/comments"));

            // 2) existing marker comment, findings present -> update
            listing[0] = "[{\"id\":5,\"body\":\"other\"},{\"id\":77,\"body\":\"" + GithubPrDeterministicFindingsPublisher.MARKER + " old\"}]";
            calls.clear();
            GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, env);
            assertTrue(calls.get(1).startsWith("PATCH /repos/o/r/issues/comments/77"), calls.toString());

            // 3) existing marker comment, no findings -> delete
            calls.clear();
            GithubPrDeterministicFindingsPublisher.publish(new AIVResult(true, List.of(GateResult.pass("syntax"))), null, null, env);
            assertEquals("DELETE /repos/o/r/issues/comments/77", calls.get(1));

            // 4) no existing comment, no findings -> only the GET
            listing[0] = "[]";
            calls.clear();
            GithubPrDeterministicFindingsPublisher.publish(new AIVResult(true, List.of()), null, null, env);
            assertEquals(1, calls.size());

            // 5) marker present but malformed listing without an id -> treated as absent
            listing[0] = "[" + GithubPrDeterministicFindingsPublisher.MARKER + "]";
            calls.clear();
            GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, env);
            assertTrue(calls.get(1).startsWith("POST"));
            listing[0] = "[{\"body\":\"" + GithubPrDeterministicFindingsPublisher.MARKER + "\"}]";
            calls.clear();
            GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, env);
            assertTrue(calls.get(1).startsWith("POST"));

            // system property override wins over env
            System.setProperty(GithubPrDeterministicFindingsPublisher.API_BASE_PROPERTY, base);
            assertEquals(base, GithubPrDeterministicFindingsPublisher.apiBase(k -> null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishSurfacesHttpFailures() throws Exception {
        int[] status = {500};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String m = ex.getRequestMethod();
            int code = "GET".equals(m) ? status[0] : ("POST".equals(m) ? status[1 % status.length] : status[0]);
            byte[] out = ("GET".equals(m) && code == 200 ? "[{\"id\":9,\"body\":\"" + GithubPrDeterministicFindingsPublisher.MARKER + "\"}]" : "err")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            Function<String, String> env = envWith(Map.of("AIV_GITHUB_FINDINGS_API_BASE",
                    "http://127.0.0.1:" + server.getAddress().getPort()));
            // GET fails
            assertThrows(IOException.class, () -> GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, env));
            // GET ok with marker, PATCH returns 500 -> IOException ; DELETE returns 500 -> IOException
            status[0] = 200;
            HttpServer s2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int[] mode = {0};
            s2.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                String m = ex.getRequestMethod();
                int code;
                String body;
                if ("GET".equals(m)) {
                    code = 200;
                    body = mode[0] == 2 ? "[]" : "[{\"id\":9,\"body\":\"" + GithubPrDeterministicFindingsPublisher.MARKER + "\"}]";
                } else {
                    code = 500;
                    body = "err";
                }
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s2.start();
            try {
                Function<String, String> env2 = envWith(Map.of("AIV_GITHUB_FINDINGS_API_BASE",
                        "http://127.0.0.1:" + s2.getAddress().getPort()));
                assertThrows(IOException.class, () -> GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, env2));
                assertThrows(IOException.class, () -> GithubPrDeterministicFindingsPublisher.publish(
                        new AIVResult(true, List.of()), null, null, env2));
                mode[0] = 2;
                assertThrows(IOException.class, () -> GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, env2));
            } finally {
                s2.stop(0);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishValidatesEnvironment() {
        assertThrows(IllegalStateException.class, () -> GithubPrDeterministicFindingsPublisher.publish(sample(), null, null, k -> null));
        assertThrows(IllegalStateException.class, () -> GithubPrDeterministicFindingsPublisher.publish(sample(), null, null,
                envWith(Map.of("GITHUB_REPOSITORY", "norepo"))));
        assertThrows(IllegalStateException.class, () -> GithubPrDeterministicFindingsPublisher.publish(sample(), null, null,
                envWith(Map.of("AIV_GITHUB_PR_NUMBER", ""))));
        assertEquals("https://api.github.com", GithubPrDeterministicFindingsPublisher.apiBase(k -> null));
    }

    private static Function<String, String> envWith(Map<String, String> overrides) {
        return key -> {
            if (overrides.containsKey(key)) {
                return overrides.get(key);
            }
            switch (key) {
                case "GITHUB_TOKEN":
                    return "t";
                case "GITHUB_REPOSITORY":
                    return "o/r";
                case "AIV_GITHUB_PR_NUMBER":
                    return "12";
                default:
                    return null;
            }
        };
    }
}
