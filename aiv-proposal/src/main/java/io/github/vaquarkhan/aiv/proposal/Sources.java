/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import io.github.vaquarkhan.aiv.util.JsonLite;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where proposals come from. Every source turns its native format into a {@link ProposalDocument}; checks
 * never see the source. Network sources use public, read-only REST endpoints (no credentials): Confluence
 * {@code /rest/api/content} and JIRA {@code /rest/api/2/issue}. Nothing is written back.
 */
final class Sources {

    /** One HTTP GET. */
    record Response(int status, String body) {
    }

    /** Seam for tests; production uses {@link HttpClient}. */
    @FunctionalInterface
    interface Http {
        Response get(String url) throws IOException, InterruptedException;
    }

    private static final Pattern DISPLAY_URL = Pattern.compile("^(https?://[^/]+(?:/confluence)?)/display/([^/]+)/([^?#]+)");
    private static final Pattern PAGES_URL = Pattern.compile("^(https?://[^/]+(?:/confluence)?)/(?:spaces/[^/]+/)?pages/(\\d+)");
    private static final Pattern PAGE_ID_PARAM = Pattern.compile("^(https?://[^/]+(?:/confluence)?)/pages/viewpage\\.action\\?pageId=(\\d+)");

    private Sources() {
    }

    static Http defaultHttp(Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
        return url -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("User-Agent", "aiv-integrity-gate").header("Accept", "application/json").GET().build();
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        };
    }

    static ProposalDocument file(Path p) throws IOException {
        String text = Files.readString(p, StandardCharsets.UTF_8);
        String name = p.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        List<ProposalDocument.Section> sections = lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")
                ? Markup.html(text) : Markup.markdown(text);
        String title = name.replaceFirst("\\.[^.]+$", "");
        for (ProposalDocument.Section s : sections) {
            if (s.level() == 1) {
                title = s.heading();
                break;
            }
        }
        return new ProposalDocument("file", p.toString().replace('\\', '/'), title, p.toString().replace('\\', '/'),
                String.valueOf(Files.getLastModifiedTime(p).toMillis()), Files.getLastModifiedTime(p).toInstant().toString(),
                sections, List.of());
    }

    /** Confluence page by URL ({@code /display/SPACE/Title}, {@code /spaces/X/pages/ID/...}, {@code viewpage.action?pageId=}). */
    static ProposalDocument confluenceUrl(Http http, String url) throws IOException, InterruptedException {
        Matcher id = PAGE_ID_PARAM.matcher(url);
        if (id.find()) {
            return confluenceById(http, id.group(1), id.group(2));
        }
        Matcher pages = PAGES_URL.matcher(url);
        if (pages.find()) {
            return confluenceById(http, pages.group(1), pages.group(2));
        }
        Matcher d = DISPLAY_URL.matcher(url);
        if (d.find()) {
            String title = URLDecoder.decode(d.group(3).replace("+", "%20"), StandardCharsets.UTF_8);
            return confluenceByTitle(http, d.group(1), d.group(2), title);
        }
        throw new IllegalArgumentException("not a Confluence page URL: " + url);
    }

    static ProposalDocument confluenceByTitle(Http http, String base, String space, String title)
            throws IOException, InterruptedException {
        String url = base + "/rest/api/content?spaceKey=" + enc(space) + "&title=" + enc(title)
                + "&expand=body.storage,version,metadata.labels";
        Object json = getJson(http, url);
        List<Object> results = JsonLite.list(JsonLite.get(json, "results"));
        if (results.isEmpty()) {
            throw new IllegalArgumentException("no Confluence page titled '" + title + "' in space " + space);
        }
        return confluenceDoc(base, results.get(0));
    }

    static ProposalDocument confluenceById(Http http, String base, String id) throws IOException, InterruptedException {
        return confluenceDoc(base, getJson(http, base + "/rest/api/content/" + enc(id) + "?expand=body.storage,version,metadata.labels"));
    }

    static ProposalDocument confluenceDoc(String base, Object page) {
        String id = JsonLite.str(JsonLite.get(page, "id"));
        String title = JsonLite.str(JsonLite.get(page, "title"));
        String storage = JsonLite.str(JsonLite.get(page, "body", "storage", "value"));
        String version = JsonLite.str(JsonLite.get(page, "version", "number"));
        String when = JsonLite.str(JsonLite.get(page, "version", "when"));
        String webui = JsonLite.str(JsonLite.get(page, "_links", "webui"));
        List<String> labels = new ArrayList<>();
        for (Object l : JsonLite.list(JsonLite.get(page, "metadata", "labels", "results"))) {
            String n = JsonLite.str(JsonLite.get(l, "name"));
            if (n != null) {
                labels.add(n);
            }
        }
        String url = webui == null ? base + "/pages/viewpage.action?pageId=" + id : base + webui;
        return new ProposalDocument("confluence", id, title, url, version == null ? "" : version, when == null ? "" : when,
                Markup.html(storage), labels);
    }

    /**
     * Number to page titles for every page in {@code space} whose title matches {@code numberPattern}
     * (group 1 is the number). Uses CQL title search with paging; the regex is the authority, CQL only narrows.
     */
    static Map<Integer, List<String>> confluenceIndex(Http http, String base, String space, String titleWord,
                                                     Pattern numberPattern, int maxPages) throws IOException, InterruptedException {
        Map<Integer, List<String>> index = new TreeMap<>();
        int start = 0;
        int limit = 200;
        for (int page = 0; page < maxPages; page++) {
            String cql = "space=\"" + space + "\" and type=page and title~\"" + titleWord + "\"";
            Object json = getJson(http, base + "/rest/api/content/search?cql=" + enc(cql) + "&limit=" + limit + "&start=" + start);
            List<Object> results = JsonLite.list(JsonLite.get(json, "results"));
            for (Object r : results) {
                String title = JsonLite.str(JsonLite.get(r, "title"));
                Matcher m = numberPattern.matcher(title == null ? "" : title);
                if (m.find() && m.start() == 0) {
                    index.computeIfAbsent(Integer.parseInt(m.group(1)), k -> new ArrayList<>()).add(title);
                }
            }
            if (results.size() < limit) {
                break;
            }
            start += limit;
        }
        return index;
    }

    /** Page ids of the {@code n} most recently created pages whose title starts with the proposal number pattern. */
    static List<String> confluenceRecent(Http http, String base, String space, String titleWord, Pattern numberPattern, int n)
            throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        int start = 0;
        int limit = 100;
        for (int page = 0; page < 20 && ids.size() < n; page++) {
            String cql = "space=\"" + space + "\" and type=page and title~\"" + titleWord + "\" order by created desc";
            Object json = getJson(http, base + "/rest/api/content/search?cql=" + enc(cql) + "&limit=" + limit + "&start=" + start);
            List<Object> results = JsonLite.list(JsonLite.get(json, "results"));
            for (Object r : results) {
                Matcher m = numberPattern.matcher(String.valueOf(JsonLite.str(JsonLite.get(r, "title"))));
                if (m.find() && m.start() == 0 && ids.size() < n) {
                    ids.add(JsonLite.str(JsonLite.get(r, "id")));
                }
            }
            if (results.size() < limit) {
                break;
            }
            start += limit;
        }
        return ids;
    }

    /** JIRA issue; the description is JIRA wiki markup. {@code headingLine} promotes template question lines to headings. */
    static ProposalDocument jira(Http http, String base, String key, Pattern headingLine) throws IOException, InterruptedException {
        Object json = getJson(http, base + "/rest/api/2/issue/" + enc(key) + "?fields=summary,description,labels,status,updated");
        String summary = JsonLite.str(JsonLite.get(json, "fields", "summary"));
        String description = JsonLite.str(JsonLite.get(json, "fields", "description"));
        String updated = JsonLite.str(JsonLite.get(json, "fields", "updated"));
        List<String> labels = new ArrayList<>();
        for (Object l : JsonLite.list(JsonLite.get(json, "fields", "labels"))) {
            labels.add(String.valueOf(l));
        }
        return new ProposalDocument("jira", key, summary, base + "/browse/" + key, updated == null ? "" : updated,
                updated == null ? "" : updated, Markup.jira(description, headingLine), labels);
    }

    private static Object getJson(Http http, String url) throws IOException, InterruptedException {
        Response r = http.get(url);
        if (r.status() != 200) {
            throw new IOException("HTTP " + r.status() + " from " + url);
        }
        return JsonLite.parse(r.body());
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
