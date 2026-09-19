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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubPrLabelPublisherTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY);
    }

    @Test
    void hasAdvisoryAiSlopDetectsWarnPathOnly() {
        var advisory = new AIVResult(true, List.of(
                GateResult.pass("syntax"),
                GateResult.advisory("design", "emoji", List.of(Finding.atLine("r", "a.py", 1, "m")))
        ));
        assertTrue(GithubPrLabelPublisher.hasAdvisoryAiSlop(advisory, GithubPrLabelPublisher.normalizeGates(null)));

        var blocking = new AIVResult(false, List.of(GateResult.fail("design", "hard")));
        assertFalse(GithubPrLabelPublisher.hasAdvisoryAiSlop(blocking, GithubPrLabelPublisher.normalizeGates(null)));

        var otherAdvisory = new AIVResult(true, List.of(GateResult.advisory("dependency", "x")));
        assertFalse(GithubPrLabelPublisher.hasAdvisoryAiSlop(
                otherAdvisory, GithubPrLabelPublisher.normalizeGates(List.of("design", "invariant", "density"))));
    }

    @Test
    void hasAdvisoryAiSlopNullResultIsFalse() {
        assertFalse(GithubPrLabelPublisher.hasAdvisoryAiSlop(null, GithubPrLabelPublisher.normalizeGates(null)));
    }

    @Test
    void normalizeGatesDefaultsAndFilters() {
        assertTrue(GithubPrLabelPublisher.normalizeGates(null).contains("design"));
        assertTrue(GithubPrLabelPublisher.normalizeGates(List.of()).contains("density"));
        var list = new java.util.ArrayList<String>();
        list.add(" Design ");
        list.add("");
        list.add(null);
        assertEquals(1, GithubPrLabelPublisher.normalizeGates(list).size());
    }

    @Test
    void parsePullRequestNumberFromEventJson() {
        assertEquals(42, GithubPrLabelPublisher.parsePullRequestNumber(
                "{\"pull_request\":{\"number\":42,\"title\":\"x\"}}"));
        assertEquals(7, GithubPrLabelPublisher.parsePullRequestNumber(
                "{\"issue\":{\"number\":7},\"comment\":{}}"));
        assertEquals(-1, GithubPrLabelPublisher.parsePullRequestNumber("{}"));
        assertEquals(-1, GithubPrLabelPublisher.parsePullRequestNumber(null));
    }

    @Test
    void applyRequiresToken() {
        assertThrows(IllegalStateException.class, () ->
                GithubPrLabelPublisher.apply(new AIVResult(true, List.of()), "aiv:ai-slop", null, System::getenv));
    }

    @Test
    void applyRequiresRepo() {
        var env = env(Map.of("GITHUB_TOKEN", "t", "AIV_GITHUB_PR_NUMBER", "1"));
        assertThrows(IllegalStateException.class, () ->
                GithubPrLabelPublisher.apply(new AIVResult(true, List.of()), "l", null, env));
    }

    @Test
    void applyRequiresPrNumber() {
        var env = env(Map.of("GITHUB_TOKEN", "t", "GITHUB_REPOSITORY", "o/r"));
        assertThrows(IllegalStateException.class, () ->
                GithubPrLabelPublisher.apply(new AIVResult(true, List.of()), "l", null, env));
    }

    @Test
    void applyRejectsBadPrNumber() {
        var env = env(Map.of(
                "GITHUB_TOKEN", "t",
                "GITHUB_REPOSITORY", "o/r",
                "AIV_GITHUB_PR_NUMBER", "nope"
        ));
        assertThrows(IllegalStateException.class, () ->
                GithubPrLabelPublisher.apply(new AIVResult(true, List.of()), "l", null, env));
    }

    @Test
    void applyAddsLabelWhenAdvisoryAiSlopPresent() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        AtomicReference<String> lastPostBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] req = exchange.getRequestBody().readAllBytes();
            if (path.contains("/labels/") && "GET".equals(method) && !path.contains("/issues/")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            if (path.endsWith("/labels") && "POST".equals(method) && !path.contains("/issues/")) {
                exchange.sendResponseHeaders(201, 0);
                exchange.close();
                return;
            }
            if (path.contains("/issues/9/labels") && "POST".equals(method)) {
                posts.incrementAndGet();
                lastPostBody.set(new String(req, StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            byte[] err = ("unhandled " + method + " " + path).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, err.length);
            exchange.getResponseBody().write(err);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "9"
            ));
            var result = new AIVResult(true, List.of(
                    GateResult.advisory("design", "Generated by ChatGPT", List.of())
            ));
            GithubPrLabelPublisher.apply(result, "aiv:ai-slop", null, env);
            assertEquals(1, posts.get());
            assertTrue(lastPostBody.get().contains("aiv:ai-slop"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void applyRemovesLabelWhenClean() throws Exception {
        AtomicInteger deletes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.contains("/labels/") && "GET".equals(method)) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            if (path.contains("/issues/3/labels/") && "DELETE".equals(method)) {
                deletes.incrementAndGet();
                exchange.sendResponseHeaders(204, 0);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "3"
            ));
            GithubPrLabelPublisher.apply(new AIVResult(true, List.of(GateResult.pass("design"))), null, null, env);
            assertEquals(1, deletes.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvePrNumberFromEventPath(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path event = dir.resolve("event.json");
        Files.writeString(event, "{\"pull_request\":{\"number\":55}}");
        var env = env(Map.of("GITHUB_EVENT_PATH", event.toString()));
        assertEquals(55, GithubPrLabelPublisher.resolvePrNumber(env));
    }

    @Test
    void apiBasePrefersPropertyThenEnv() {
        System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://prop/");
        assertEquals("http://prop", GithubPrLabelPublisher.apiBase(env(Map.of("AIV_GITHUB_API_BASE", "http://env"))));
        System.clearProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY);
        assertEquals("http://env", GithubPrLabelPublisher.apiBase(env(Map.of("AIV_GITHUB_API_BASE", "http://env/"))));
        assertEquals("https://api.github.com", GithubPrLabelPublisher.apiBase(env(Map.of())));
    }

    @Test
    void applyFailsWhenCreateLabelErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, 0);
            } else {
                byte[] err = "no".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                exchange.getResponseBody().write(err);
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "1"
            ));
            assertThrows(IOException.class, () -> GithubPrLabelPublisher.apply(
                    new AIVResult(true, List.of(GateResult.advisory("density", "low"))), "x", null, env));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsePullRequestNumberEdgeCases() {
        assertEquals(-1, GithubPrLabelPublisher.parsePullRequestNumber(""));
        assertEquals(-1, GithubPrLabelPublisher.parsePullRequestNumber(
                "{\"pull_request\":{" + "x".repeat(900) + "\"number\":1}}"));
        assertEquals(-1, GithubPrLabelPublisher.parsePullRequestNumber(
                "{\"pull_request\":{\"number\" 1}}")); // missing colon
        assertEquals(8, GithubPrLabelPublisher.parsePullRequestNumber(
                "{\"pull_request\":{\"number\":   8}}"));
        assertEquals(-1, GithubPrLabelPublisher.parsePullRequestNumber(
                "{\"pull_request\":{\"number\":}}"));
        assertEquals(-1, GithubPrLabelPublisher.parsePullRequestNumber(
                "{\"pull_request\":{\"number\":99999999999999999999}}"));
    }

    @Test
    void applyFailsWhenGetLabelErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] err = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, err.length);
            exchange.getResponseBody().write(err);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "1"
            ));
            assertThrows(IOException.class, () -> GithubPrLabelPublisher.apply(
                    new AIVResult(true, List.of(GateResult.advisory("density", "low"))), "x", null, env));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void applyFailsWhenAddLabelErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.contains("/labels/") && "GET".equals(method) && !path.contains("/issues/")) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            if (path.contains("/issues/") && "POST".equals(method)) {
                byte[] err = "no".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(403, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "2"
            ));
            assertThrows(IOException.class, () -> GithubPrLabelPublisher.apply(
                    new AIVResult(true, List.of(GateResult.advisory("design", "m"))), "lab", null, env));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void applyFailsWhenRemoveLabelErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            if ("DELETE".equals(method)) {
                byte[] err = "no".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "4"
            ));
            assertThrows(IOException.class, () -> GithubPrLabelPublisher.apply(
                    new AIVResult(true, List.of(GateResult.pass("design"))), "lab", null, env));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void applyAcceptsCreateLabelAlreadyExists() throws Exception {
        AtomicInteger issuePosts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.contains("/labels/") && "GET".equals(method) && !path.contains("/issues/")) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            if (path.endsWith("/labels") && "POST".equals(method) && !path.contains("/issues/")) {
                exchange.sendResponseHeaders(422, 0);
                exchange.close();
                return;
            }
            if (path.contains("/issues/") && "POST".equals(method)) {
                issuePosts.incrementAndGet();
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "5"
            ));
            GithubPrLabelPublisher.apply(
                    new AIVResult(true, List.of(GateResult.advisory("invariant", "m"))), "lab", null, env);
            assertEquals(1, issuePosts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void applyTreatsMissingLabelOnDeleteAsSuccess() throws Exception {
        AtomicInteger deletes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletes.incrementAndGet();
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubPrLabelPublisher.LABELS_API_BASE_PROPERTY, "http://127.0.0.1:" + port);
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_PR_NUMBER", "6"
            ));
            GithubPrLabelPublisher.apply(new AIVResult(true, List.of()), "lab", null, env);
            assertEquals(1, deletes.get());
        } finally {
            server.stop(0);
        }
    }

    private static Function<String, String> env(Map<String, String> map) {
        Map<String, String> m = new HashMap<>(map);
        return m::get;
    }
}
