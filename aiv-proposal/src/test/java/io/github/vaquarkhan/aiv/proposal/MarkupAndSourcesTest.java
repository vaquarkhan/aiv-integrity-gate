/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aiv.util.JsonLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkupAndSourcesTest {

    @Test
    void htmlSectionsTextAndLinks() {
        List<ProposalDocument.Section> s = Markup.html("<p>intro &amp; more</p><h1>Status</h1><p>a<br/>b</p>"
                + "<h2 class=\"x\">Sub <em>heading</em></h2><ul><li>one</li><li>two&#33; &#x41; &rsquo;q&rdquo; &nbsp;&unknown; &amp</li></ul>"
                + "<a href=\"https://l/x?a=1&amp;b=2\">t</a><ac:link><ri:page ri:content-title=\"KIP-1: a\" /></ac:link>"
                + "<ac:link><ri:url ri:value=\"https://u\" /></ac:link>");
        assertEquals(3, s.size());
        assertEquals("", s.get(0).heading());
        assertEquals(0, s.get(0).level());
        assertEquals("intro & more", s.get(0).text());
        assertEquals("Status", s.get(1).heading());
        assertEquals("a\nb", s.get(1).text());
        assertEquals("Sub heading", s.get(2).heading());
        assertEquals(2, s.get(2).level());
        assertEquals("one\ntwo! A 'q\" &unknown; &amp\nt", s.get(2).text());
        assertEquals(List.of("https://l/x?a=1&b=2", "https://u", "page:KIP-1: a"), s.get(2).links());
        assertEquals(List.of(), Markup.html(null));
        assertEquals(List.of(), Markup.html("<p> </p>"));
        assertEquals("-", Markup.entities("&mdash;"));
        assertEquals("'", Markup.entities("&apos;"));
        assertEquals("<b>", Markup.entities("&lt;b&gt;"));
    }

    @Test
    void markdownAndJiraDialects() {
        List<ProposalDocument.Section> md = Markup.markdown("pre\n# Title #\n\n## Motivation\nSee [x](https://a/b) and <https://c/d> and https://e/f.\n");
        assertEquals(List.of("", "Title", "Motivation"), md.stream().map(ProposalDocument.Section::heading).toList());
        assertEquals(List.of("https://a/b", "https://c/d", "https://e/f."), md.get(2).links());
        assertEquals(List.of(), Markup.markdown(null));
        List<ProposalDocument.Section> j = Markup.jira("h1. *Intro*\nbody [doc|https://g/x] https://h/y\nQ1. what\nans", Pattern.compile("^Q1"));
        assertEquals(List.of("Intro", "Q1. what"), j.stream().map(ProposalDocument.Section::heading).toList());
        assertEquals(List.of("https://g/x", "https://h/y"), j.get(0).links());
        assertEquals(1, Markup.jira("Q1. x", null).size(), "without a heading_line regex the line stays body text");
    }

    @Test
    void fileSourceReadsMarkdownAndHtml(@TempDir Path dir) throws IOException {
        Path md = dir.resolve("kip-9.md");
        Files.writeString(md, "# KIP-9: Thing\n## Motivation\nwhy\n", StandardCharsets.UTF_8);
        ProposalDocument d = Sources.file(md);
        assertEquals("file", d.source());
        assertEquals("KIP-9: Thing", d.title());
        assertEquals(2, d.sections().size());
        Path html = dir.resolve("p.HTML");
        Files.writeString(html, "<h2>Motivation</h2><p>x</p>", StandardCharsets.UTF_8);
        assertEquals("p", Sources.file(html).title(), "no level-1 heading: file name is the title");
        Path htm = dir.resolve("q.htm");
        Files.writeString(htm, "<h1>T</h1>", StandardCharsets.UTF_8);
        assertEquals("T", Sources.file(htm).title());
        Path x = dir.resolve("r.xhtml");
        Files.writeString(x, "<p>x</p>", StandardCharsets.UTF_8);
        assertEquals(1, Sources.file(x).sections().size());
    }

    @Test
    void confluenceUrlsTitlesIdsIndexAndRecent() throws Exception {
        List<String> calls = new ArrayList<>();
        String page = ProposalFixtures.page("77", "KIP-77: x", 3, ProposalFixtures.GOOD_KIP, "kip");
        Sources.Http http = url -> {
            calls.add(url);
            if (url.contains("/content/search")) {
                boolean second = url.contains("start=200") || url.contains("start=100");
                StringBuilder r = new StringBuilder("{\"results\":[");
                int n = url.contains("limit=200") ? (second ? 2 : 200) : (second ? 1 : 100);
                for (int i = 0; i < n; i++) {
                    int num = second ? 9000 + i : i;
                    String title = i == 3 ? "Archive of KIP-3" : "KIP-" + num + ": t" + i;
                    r.append(i == 0 ? "" : ",").append("{\"id\":\"").append(num).append("\",\"title\":\"").append(title).append("\"}");
                }
                if (second) {
                    r.append(",{\"id\":\"x\",\"title\":\"KIP-0: dup\"},{\"id\":\"y\"}");
                }
                return new Sources.Response(200, r.append("]}").toString());
            }
            if (url.contains("title=Missing")) {
                return new Sources.Response(200, "{\"results\":[]}");
            }
            if (url.contains("?spaceKey=")) {
                return new Sources.Response(200, "{\"results\":[" + page + "]}");
            }
            if (url.contains("/content/404")) {
                return new Sources.Response(404, "nope");
            }
            return new Sources.Response(200, page);
        };
        String base = "https://cwiki.apache.org/confluence";
        ProposalDocument byDisplay = Sources.confluenceUrl(http, base + "/display/KAFKA/KIP-77%3A+x");
        assertEquals("KIP-77: x", byDisplay.title());
        assertTrue(calls.get(0).contains("spaceKey=KAFKA&title=KIP-77%3A%20x"), calls.get(0));
        assertEquals("3", byDisplay.version());
        assertEquals(List.of("kip"), byDisplay.labels());
        assertEquals(base + "/spaces/KAFKA/pages/77", byDisplay.url());
        Sources.confluenceUrl(http, base + "/spaces/KAFKA/pages/77/KIP-77+x");
        assertTrue(calls.get(1).endsWith("/rest/api/content/77?expand=body.storage,version,metadata.labels"));
        Sources.confluenceUrl(http, base + "/pages/viewpage.action?pageId=78");
        assertTrue(calls.get(2).contains("/rest/api/content/78?"));
        assertThrows(IllegalArgumentException.class, () -> Sources.confluenceUrl(http, "https://example.org/wiki/x"));
        assertThrows(IllegalArgumentException.class, () -> Sources.confluenceByTitle(http, base, "KAFKA", "Missing"));
        assertThrows(IOException.class, () -> Sources.confluenceById(http, base, "404"));

        Map<Integer, List<String>> index = Sources.confluenceIndex(http, base, "KAFKA", "KIP", Pattern.compile("^KIP-(\\d+)"), 5);
        assertEquals(List.of("KIP-0: t0", "KIP-0: dup"), index.get(0));
        assertTrue(index.containsKey(9001));
        assertTrue(!index.containsKey(3), "a title that does not start with the number is not a proposal page");
        assertEquals(1, Sources.confluenceIndex(http, base, "KAFKA", "KIP", Pattern.compile("^KIP-(\\d+)"), 1).get(0).size());

        List<String> recent = Sources.confluenceRecent(http, base, "KAFKA", "KIP", Pattern.compile("^KIP-(\\d+)"), 3);
        assertEquals(List.of("0", "1", "2"), recent);
        assertEquals(101, Sources.confluenceRecent(http, base, "KAFKA", "KIP", Pattern.compile("^KIP-(\\d+)"), 101).size());
        assertEquals(101, Sources.confluenceRecent(http, base, "KAFKA", "KIP", Pattern.compile("^KIP-(\\d+)"), 500).size(),
                "stops when the last page is short");

        ProposalDocument noWebui = Sources.confluenceDoc(base, JsonLite.parse("{\"id\":\"5\",\"title\":\"t\",\"metadata\":{\"labels\":{\"results\":[{}]}}}"));
        assertEquals(base + "/pages/viewpage.action?pageId=5", noWebui.url());
        assertEquals("", noWebui.version());
        assertEquals("", noWebui.lastModified());
        assertEquals(List.of(), noWebui.labels());
    }

    @Test
    void jiraIssue() throws Exception {
        ProposalDocument d = Sources.jira(url -> new Sources.Response(200, ProposalFixtures.SPIP_JIRA), "https://issues.apache.org/jira",
                "SPARK-1", Pattern.compile("^Q[1-8]\\s*[.:)]", Pattern.CASE_INSENSITIVE));
        assertEquals("[SPIP] Faster shuffle", d.title());
        assertEquals("https://issues.apache.org/jira/browse/SPARK-1", d.url());
        assertEquals(List.of("SPIP"), d.labels());
        assertEquals(List.of("Q1. What are you trying to do?", "Q2. What problem is this proposal NOT designed to solve?"),
                d.sections().stream().map(ProposalDocument.Section::heading).toList());
        ProposalDocument bare = Sources.jira(url -> new Sources.Response(200, "{\"fields\":{}}"), "https://j", "X-1", null);
        assertEquals("", bare.version());
        assertEquals(List.of(), bare.sections());
    }

    @Test
    void defaultHttpTalksToARealServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            Sources.Response r = Sources.defaultHttp(Duration.ofSeconds(5)).get("http://127.0.0.1:" + server.getAddress().getPort() + "/x");
            assertEquals(200, r.status());
            assertEquals("{\"ok\":true}", r.body());
        } finally {
            server.stop(0);
        }
        assertEquals("a%20b%2Fc", Sources.enc("a b/c"));
    }
}
