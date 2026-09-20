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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubPrReviewCommentsPublisherTest {

    @AfterEach
    void clearProp() {
        System.clearProperty(GithubPrReviewCommentsPublisher.COMMENTS_API_BASE_PROPERTY);
    }

    @Test
    void hintForKnownRules() {
        assertTrue(GithubPrReviewCommentsPublisher.hintFor("invariant.merge-conflict").contains("conflict"));
        assertTrue(GithubPrReviewCommentsPublisher.hintFor("security.aws-key").toLowerCase().contains("aws"));
        assertTrue(GithubPrReviewCommentsPublisher.hintFor("security.other").toLowerCase().contains("secret"));
        assertTrue(GithubPrReviewCommentsPublisher.hintFor("invariant.xyz").toLowerCase().contains("junk")
                || GithubPrReviewCommentsPublisher.hintFor("invariant.xyz").toLowerCase().contains("remove"));
        assertTrue(GithubPrReviewCommentsPublisher.hintFor(null).contains("Address"));
        assertTrue(GithubPrReviewCommentsPublisher.buildBody(
                Finding.atLine("syntax.parse", "a.js", 1, "bad")).contains("Fix:"));
    }

    @Test
    void publishNoopWhenPassed() throws Exception {
        GithubPrReviewCommentsPublisher.publish(new AIVResult(true, List.of(GateResult.pass("x"))), k -> null);
    }

    @Test
    void publishRequiresToken() {
        var result = new AIVResult(false, List.of(GateResult.fail("invariant", "x",
                List.of(Finding.atLine("invariant.merge-conflict", "a.java", 1, "c")))));
        assertThrows(IllegalStateException.class,
                () -> GithubPrReviewCommentsPublisher.publish(result, k -> null));
    }

    @Test
    void publishRequiresRepoShaAndPr() {
        var result = new AIVResult(false, List.of(GateResult.fail("invariant", "x",
                List.of(Finding.atLine("invariant.merge-conflict", "a.java", 1, "c")))));
        assertThrows(IllegalStateException.class, () -> GithubPrReviewCommentsPublisher.publish(result,
                k -> Map.of("GITHUB_TOKEN", "t").get(k)));
        assertThrows(IllegalStateException.class, () -> GithubPrReviewCommentsPublisher.publish(result,
                k -> Map.of("GITHUB_TOKEN", "t", "GITHUB_REPOSITORY", "bad").get(k)));
        assertThrows(IllegalStateException.class, () -> GithubPrReviewCommentsPublisher.publish(result,
                k -> Map.of("GITHUB_TOKEN", "t", "GITHUB_REPOSITORY", "o/r").get(k)));
        assertThrows(IllegalStateException.class, () -> GithubPrReviewCommentsPublisher.publish(result,
                k -> Map.of("GITHUB_TOKEN", "t", "GITHUB_REPOSITORY", "o/r", "GITHUB_SHA", "abc").get(k)));
    }

    @Test
    void publishSkipsPassedGatesAndBlankPaths() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/pulls/3/comments", exchange -> {
            posts.incrementAndGet();
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty(GithubPrReviewCommentsPublisher.COMMENTS_API_BASE_PROPERTY,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var result = new AIVResult(false, List.of(
                    GateResult.pass("syntax"),
                    GateResult.fail("invariant", "x", List.of(
                            Finding.atLine("invariant.merge-conflict", "   ", 1, "c"),
                            Finding.atLine("density.low", "a.java", 1, "d")))));
            Function<String, String> env = k -> Map.of(
                    "GITHUB_TOKEN", "t",
                    "GITHUB_REPOSITORY", "o/r",
                    "AIV_GITHUB_HEAD_SHA", "sha",
                    "AIV_GITHUB_PR_NUMBER", "3"
            ).get(k);
            GithubPrReviewCommentsPublisher.publish(result, env);
            assertTrue(posts.get() >= 1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishHttpFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/pulls/1/comments", exchange -> {
            byte[] body = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty(GithubPrReviewCommentsPublisher.COMMENTS_API_BASE_PROPERTY,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var result = new AIVResult(false, List.of(GateResult.fail("invariant", "x",
                    List.of(Finding.atLine("invariant.merge-conflict", "a.java", 1, "c")))));
            Function<String, String> env = k -> Map.of(
                    "GITHUB_TOKEN", "t",
                    "GITHUB_REPOSITORY", "o/r",
                    "GITHUB_SHA", "abc",
                    "AIV_GITHUB_PR_NUMBER", "1"
            ).get(k);
            assertThrows(java.io.IOException.class,
                    () -> GithubPrReviewCommentsPublisher.publish(result, env));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishEmptyFindingsReturns() throws Exception {
        var result = new AIVResult(false, List.of(GateResult.fail("invariant", "x", List.of())));
        Function<String, String> env = k -> Map.of(
                "GITHUB_TOKEN", "t",
                "GITHUB_REPOSITORY", "o/r",
                "GITHUB_SHA", "abc",
                "AIV_GITHUB_PR_NUMBER", "9"
        ).get(k);
        GithubPrReviewCommentsPublisher.publish(result, env);
    }

    @Test
    void hintFallbackAndApiBaseOverride() {
        assertTrue(GithubPrReviewCommentsPublisher.hintFor("invariant.merge-conflict-extra").contains("conflict"));
        assertTrue(GithubPrReviewCommentsPublisher.hintFor("density.low").contains("explain")
                || GithubPrReviewCommentsPublisher.hintFor("density.low").contains("Address"));
        System.setProperty(GithubPrReviewCommentsPublisher.COMMENTS_API_BASE_PROPERTY, "http://example/");
        assertTrue(GithubPrReviewCommentsPublisher.apiBase(k -> null).endsWith("example")
                || GithubPrReviewCommentsPublisher.apiBase(k -> null).contains("example"));
        System.clearProperty(GithubPrReviewCommentsPublisher.COMMENTS_API_BASE_PROPERTY);
        assertTrue(GithubPrReviewCommentsPublisher.apiBase(k -> "http://env-base").contains("env-base"));
    }

    @Test
    void publishCapsAtMaxComments() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/pulls/2/comments", exchange -> {
            posts.incrementAndGet();
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty(GithubPrReviewCommentsPublisher.COMMENTS_API_BASE_PROPERTY,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            List<Finding> many = new java.util.ArrayList<>();
            for (int i = 0; i < 35; i++) {
                many.add(Finding.atLine("invariant.merge-conflict", "f" + i + ".java", 1, "c"));
            }
            var result = new AIVResult(false, List.of(GateResult.fail("invariant", "x", many)));
            Function<String, String> env = k -> Map.of(
                    "GITHUB_TOKEN", "t",
                    "GITHUB_REPOSITORY", "o/r",
                    "GITHUB_SHA", "abc",
                    "AIV_GITHUB_PR_NUMBER", "2"
            ).get(k);
            GithubPrReviewCommentsPublisher.publish(result, env);
            assertTrue(posts.get() == 30);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishPostsComment() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/pulls/7/comments", exchange -> {
            posts.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("commit_id"));
            assertTrue(body.contains("Fix:"));
            exchange.sendResponseHeaders(201, 0);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty(GithubPrReviewCommentsPublisher.COMMENTS_API_BASE_PROPERTY,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var result = new AIVResult(false, List.of(GateResult.fail("invariant", "x",
                    List.of(Finding.atLine("invariant.merge-conflict", "a.java", 2, "marker")))));
            Function<String, String> env = k -> Map.of(
                    "GITHUB_TOKEN", "t",
                    "GITHUB_REPOSITORY", "o/r",
                    "GITHUB_SHA", "abc",
                    "AIV_GITHUB_PR_NUMBER", "7"
            ).get(k);
            GithubPrReviewCommentsPublisher.publish(result, env);
            assertTrue(posts.get() >= 1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void escapeJson() {
        assertTrue(GithubPrReviewCommentsPublisher.escapeJson("a\"b\nc").contains("\\\""));
        assertTrue(GithubPrReviewCommentsPublisher.escapeJson(null).isEmpty());
    }

    @Test
    void apiBaseDefault() {
        assertTrue(GithubPrReviewCommentsPublisher.apiBase(k -> null).contains("api.github.com"));
    }
}
