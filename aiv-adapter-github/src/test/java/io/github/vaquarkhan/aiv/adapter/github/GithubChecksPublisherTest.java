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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubChecksPublisherTest {

    @AfterEach
    void clearChecksUrlProperty() {
        System.clearProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY);
    }

    @Test
    void publishRequiresGithubToken() {
        var result = new AIVResult(true, List.of(GateResult.pass("density")));
        assertThrows(IllegalStateException.class, () -> GithubChecksPublisher.publish(result, "1.0.0", System::getenv));
    }

    @Test
    void publishRequiresValidRepository() {
        var result = new AIVResult(true, List.of(GateResult.pass("density")));
        var env = env(Map.of(
                "GITHUB_TOKEN", "t",
                "GITHUB_SHA", "abc"
        ));
        assertThrows(IllegalStateException.class, () -> GithubChecksPublisher.publish(result, "1.0.0", env));
    }

    @Test
    void publishRequiresRepositoryWithSlash() {
        var result = new AIVResult(true, List.of(GateResult.pass("density")));
        var env = env(Map.of(
                "GITHUB_TOKEN", "t",
                "GITHUB_REPOSITORY", "norepo",
                "GITHUB_SHA", "abc"
        ));
        assertThrows(IllegalStateException.class, () -> GithubChecksPublisher.publish(result, "1.0.0", env));
    }

    @Test
    void publishRequiresSha() {
        var result = new AIVResult(true, List.of(GateResult.pass("density")));
        var env = env(Map.of(
                "GITHUB_TOKEN", "t",
                "GITHUB_REPOSITORY", "o/r"
        ));
        assertThrows(IllegalStateException.class, () -> GithubChecksPublisher.publish(result, "1.0.0", env));
    }

    @Test
    void publishRequiresNonBlankSha() {
        var result = new AIVResult(true, List.of(GateResult.pass("density")));
        var env = env(Map.of(
                "GITHUB_TOKEN", "t",
                "GITHUB_REPOSITORY", "o/r",
                "GITHUB_SHA", "   "
        ));
        assertThrows(IllegalStateException.class, () -> GithubChecksPublisher.publish(result, "1.0.0", env));
    }

    @Test
    void publishRejectsBlankToken() {
        var result = new AIVResult(true, List.of(GateResult.pass("density")));
        var env = env(Map.of(
                "GITHUB_TOKEN", "   ",
                "GITHUB_REPOSITORY", "o/r",
                "GITHUB_SHA", "a"
        ));
        assertThrows(IllegalStateException.class, () -> GithubChecksPublisher.publish(result, "1.0.0", env));
    }

    @Test
    void publishUsesGithubShaWhenHeadIsWhitespace() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            assertTrue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).contains("realsha"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "AIV_GITHUB_HEAD_SHA", "   ",
                    "GITHUB_SHA", "realsha"
            ));
            GithubChecksPublisher.publish(new AIVResult(true, List.of()), "1.0", env);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveChecksUriUsesDefaultApi() {
        var env = env(Map.of());
        URI u = GithubChecksPublisher.resolveChecksUri("acme", "proj", env);
        assertEquals("https://api.github.com/repos/acme/proj/check-runs", u.toString());
    }

    @Test
    void resolveChecksUriUsesEnvOverride() {
        var env = env(Map.of("AIV_GITHUB_CHECKS_URL", "http://localhost:9999/custom"));
        URI u = GithubChecksPublisher.resolveChecksUri("acme", "proj", env);
        assertEquals("http://localhost:9999/custom", u.toString());
    }

    @Test
    void resolveChecksUriPropertyWinsOverEnv() {
        System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY, "http://127.0.0.1:1/prop");
        var env = env(Map.of("AIV_GITHUB_CHECKS_URL", "http://ignored/x"));
        URI u = GithubChecksPublisher.resolveChecksUri("a", "b", env);
        assertEquals("http://127.0.0.1:1/prop", u.toString());
    }

    @Test
    void resolveChecksUriBlankPropertyFallsBackToEnv() {
        System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY, "  ");
        var env = env(Map.of("AIV_GITHUB_CHECKS_URL", "http://fallback/z"));
        URI u = GithubChecksPublisher.resolveChecksUri("a", "b", env);
        assertEquals("http://fallback/z", u.toString());
    }

    @Test
    void resolveChecksUriBlankEnvOverrideUsesDefaultApi() {
        var env = env(Map.of("AIV_GITHUB_CHECKS_URL", "   "));
        URI u = GithubChecksPublisher.resolveChecksUri("acme", "proj", env);
        assertEquals("https://api.github.com/repos/acme/proj/check-runs", u.toString());
    }

    @Test
    void publishPostsToChecksEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            assertTrue(new String(body, StandardCharsets.UTF_8).contains("\"conclusion\":\"success\""));
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "GITHUB_SHA", "deadbeef"
            ));
            var result = new AIVResult(true, List.of(GateResult.pass("density")), List.of("note"));
            GithubChecksPublisher.publish(result, "9.9.9", env);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishUsesHeadShaWhenSet() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            assertTrue(new String(body, StandardCharsets.UTF_8).contains("\"head_sha\":\"aaaabbbb\""));
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "AIV_GITHUB_HEAD_SHA", "aaaabbbb",
                    "GITHUB_SHA", "ignored"
            ));
            GithubChecksPublisher.publish(new AIVResult(true, List.of()), "1.0", env);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishFailsOnNon2xx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            byte[] err = "no".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, err.length);
            exchange.getResponseBody().write(err);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "GITHUB_SHA", "cafe"
            ));
            List<Finding> findings = List.of(Finding.atLine("x", "w\\here\\file.java", 3, "bad\"msg"));
            var result = new AIVResult(false, List.of(
                    GateResult.pass("ok"),
                    GateResult.advisory("adv", "a", List.of(Finding.atLine("a", "z.txt", 1, "w"))),
                    GateResult.fail("bad", "line1\nline2", findings)
            ));
            IOException ex = assertThrows(IOException.class, () -> GithubChecksPublisher.publish(result, "1.0", env));
            assertTrue(ex.getMessage().contains("HTTP 500"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishSucceedsOnHttp200() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "GITHUB_SHA", "cafe"
            ));
            var result = new AIVResult(false, List.of(GateResult.fail("g", "")));
            GithubChecksPublisher.publish(result, "1.0", env);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishThrowsOnHttp199() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            exchange.sendResponseHeaders(199, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "GITHUB_SHA", "cafe"
            ));
            assertThrows(IOException.class, () -> GithubChecksPublisher.publish(
                    new AIVResult(true, List.of()), "1.0", env));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void firstNonBlankPrefersFirstArgument() {
        assertEquals("a", GithubChecksPublisher.firstNonBlank("a", "b"));
        assertEquals("b", GithubChecksPublisher.firstNonBlank(null, "b"));
        assertEquals("b", GithubChecksPublisher.firstNonBlank("  ", "b"));
        assertNull(GithubChecksPublisher.firstNonBlank(null, null));
        assertNull(GithubChecksPublisher.firstNonBlank(" ", " "));
    }

    @Test
    void escapeJsonEscapesSpecials() {
        assertEquals("", GithubChecksPublisher.escapeJson(null));
        assertEquals("\\\\", GithubChecksPublisher.escapeJson("\\"));
        assertEquals("\\r", GithubChecksPublisher.escapeJson("\r"));
        assertEquals("\\t", GithubChecksPublisher.escapeJson("\t"));
        assertEquals("\\u0001", GithubChecksPublisher.escapeJson("\u0001"));
    }

    @Test
    void annotationsCappedAtFifty() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/n/check-runs", exchange -> {
            String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int from = 0;
            int count = 0;
            while (true) {
                int i = json.indexOf("\"path\"", from);
                if (i < 0) {
                    break;
                }
                count++;
                from = i + 1;
            }
            assertEquals(50, count);
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(GithubChecksPublisher.CHECKS_URL_PROPERTY,
                    "http://127.0.0.1:" + port + "/repos/o/n/check-runs");
            var env = env(Map.of(
                    "GITHUB_TOKEN", "tok",
                    "GITHUB_REPOSITORY", "o/n",
                    "GITHUB_SHA", "cafe"
            ));
            List<Finding> many = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                many.add(Finding.atLine("r", "F.java", i + 1, "m"));
            }
            var result = new AIVResult(false, List.of(GateResult.fail("g", "x", many)));
            GithubChecksPublisher.publish(result, "1.0", env);
        } finally {
            server.stop(0);
        }
    }

    private static Function<String, String> env(Map<String, String> map) {
        Map<String, String> m = new HashMap<>(map);
        return m::get;
    }
}
