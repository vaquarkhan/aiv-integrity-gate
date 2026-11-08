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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubReviewTierLabelPublisherTest {

    @AfterEach
    void clear() {
        System.clearProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY);
    }

    private static AIVResult routed(String message) {
        GateResult routing = new GateResult("review-routing", true, message, false,
                List.of(Finding.atLine("review-routing.tier", "(diff)", 1, message)));
        return new AIVResult(true, List.of(GateResult.pass("syntax"), routing));
    }

    private static Function<String, String> env(Map<String, String> m) {
        return m::get;
    }

    private static final Map<String, String> ENV = Map.of(
            "GITHUB_TOKEN", "tok", "GITHUB_REPOSITORY", "o/r", "AIV_GITHUB_PR_NUMBER", "7");

    /** Minimal fake GitHub labels API: records calls, serves the PR's current labels. */
    private static final class FakeGithub implements AutoCloseable {
        final HttpServer server;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> prLabels;
        int listStatus = 200;
        boolean labelExists = false;

        FakeGithub(List<String> labels) throws IOException {
            prLabels = new CopyOnWriteArrayList<>(labels);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String method = ex.getRequestMethod();
                String path = ex.getRequestURI().getPath();
                calls.add(method + " " + path);
                int code;
                String body = "{}";
                if ("GET".equals(method) && path.endsWith("/issues/7/labels")) {
                    code = listStatus;
                    StringBuilder b = new StringBuilder("[");
                    for (int i = 0; i < prLabels.size(); i++) {
                        b.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"")
                                .append(prLabels.get(i)).append("\"}");
                    }
                    body = code == 200 ? b.append("]").toString() : "boom";
                } else if ("GET".equals(method)) {
                    code = labelExists ? 200 : 404;
                } else if ("POST".equals(method) && path.endsWith("/labels") && path.contains("/issues/")) {
                    code = 200;
                } else if ("POST".equals(method)) {
                    code = 201;
                } else {
                    code = 204;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
                if (code != 204) {
                    ex.getResponseBody().write(bytes);
                }
                ex.close();
            });
            server.start();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY,
                    "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void tierParsing() {
        assertEquals("balanced", GithubReviewTierLabelPublisher.tier(routed("tier=balanced; rule 1")));
        assertEquals("lite", GithubReviewTierLabelPublisher.tier(routed("tier=lite")));
        assertEquals("", GithubReviewTierLabelPublisher.tier(routed("tier=Bad Name; x")));
        assertEquals("", GithubReviewTierLabelPublisher.tier(routed("nope")));
        assertEquals("", GithubReviewTierLabelPublisher.tier(null));
        assertEquals("", GithubReviewTierLabelPublisher.tier(new AIVResult(true, List.of(GateResult.pass("syntax")))));
        GateResult other = new GateResult("review-routing", true, "x", false,
                List.of(Finding.atLine("other.rule", "(diff)", 1, "tier=lite"),
                        Finding.atLine("review-routing.tier", "(diff)", 1, null)));
        assertEquals("", GithubReviewTierLabelPublisher.tier(new AIVResult(true, List.of(other))));
    }

    @Test
    void replacesOtherTierLabelsAndAddsTheNewOne() throws Exception {
        try (FakeGithub gh = new FakeGithub(List.of("bug", "aiv:review-lite"))) {
            String label = GithubReviewTierLabelPublisher.publish(routed("tier=balanced; rule 1"), null, env(ENV));
            assertEquals("aiv:review-balanced", label);
            assertTrue(gh.calls.contains("DELETE /repos/o/r/issues/7/labels/aiv:review-lite"), gh.calls.toString());
            assertTrue(gh.calls.contains("POST /repos/o/r/labels"), "label created when missing");
            assertTrue(gh.calls.contains("POST /repos/o/r/issues/7/labels"));
            assertTrue(gh.calls.stream().noneMatch(c -> c.contains("/labels/bug")), "unrelated labels untouched");
        }
    }

    @Test
    void noWritesWhenTheLabelIsAlreadyCorrect() throws Exception {
        try (FakeGithub gh = new FakeGithub(List.of("aiv:review-none"))) {
            GithubReviewTierLabelPublisher.publish(routed("tier=none; rule 0"), "aiv:review-", env(ENV));
            assertEquals(List.of("GET /repos/o/r/issues/7/labels"), gh.calls);
        }
    }

    @Test
    void customPrefix() throws Exception {
        try (FakeGithub gh = new FakeGithub(List.of("ai-tier/lite", "aiv:review-lite"))) {
            gh.labelExists = true;
            assertEquals("ai-tier/balanced",
                    GithubReviewTierLabelPublisher.publish(routed("tier=balanced"), " ai-tier/ ", env(ENV)));
            assertTrue(gh.calls.contains("DELETE /repos/o/r/issues/7/labels/ai-tier/lite"), gh.calls.toString());
            assertTrue(gh.calls.stream().noneMatch(c -> c.endsWith("aiv:review-lite")));
            assertTrue(gh.calls.stream().noneMatch(c -> c.equals("POST /repos/o/r/labels")));
        }
    }

    @Test
    void failsLoudlyOnMisconfigurationAndApiErrors() throws Exception {
        AIVResult noTier = new AIVResult(true, List.of(GateResult.pass("syntax")));
        assertThrows(IllegalStateException.class, () -> GithubReviewTierLabelPublisher.publish(noTier, null, env(ENV)));
        AIVResult ok = routed("tier=lite");
        assertThrows(IllegalStateException.class, () -> GithubReviewTierLabelPublisher.publish(ok, null,
                env(Map.of("GITHUB_REPOSITORY", "o/r", "AIV_GITHUB_PR_NUMBER", "7"))));
        assertThrows(IllegalStateException.class, () -> GithubReviewTierLabelPublisher.publish(ok, null,
                env(Map.of("GITHUB_TOKEN", " ", "GITHUB_REPOSITORY", "o/r"))));
        assertThrows(IllegalStateException.class, () -> GithubReviewTierLabelPublisher.publish(ok, null,
                env(Map.of("GITHUB_TOKEN", "t", "GITHUB_REPOSITORY", "norepo"))));
        assertThrows(IllegalStateException.class, () -> GithubReviewTierLabelPublisher.publish(ok, null,
                env(Map.of("GITHUB_TOKEN", "t"))));
        Map<String, String> noPr = new HashMap<>(ENV);
        noPr.remove("AIV_GITHUB_PR_NUMBER");
        assertThrows(IllegalStateException.class, () -> GithubReviewTierLabelPublisher.publish(ok, null, env(noPr)));
        try (FakeGithub gh = new FakeGithub(new ArrayList<>())) {
            gh.listStatus = 500;
            assertThrows(IOException.class, () -> GithubReviewTierLabelPublisher.publish(ok, null, env(ENV)));
        }
    }
}
