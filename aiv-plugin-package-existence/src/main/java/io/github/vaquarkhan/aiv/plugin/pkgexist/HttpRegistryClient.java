/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.pkgexist;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Registry lookups over HTTP. Only a definitive {@code 200} (exists) or {@code 404} (does not exist)
 * is trusted; anything else (network error, timeout, 429, 5xx) is {@code Optional.empty()}.
 *
 * <p>Endpoints are overridable (mirrors, air-gapped proxies) via config keys passed to the constructor.
 */
public final class HttpRegistryClient implements RegistryClient {

    static final String DEFAULT_PYPI = "https://pypi.org/simple/";
    static final String DEFAULT_NPM = "https://registry.npmjs.org/";
    static final String DEFAULT_MAVEN = "https://repo1.maven.org/maven2/";

    /** Seam: maps a URL to its HTTP status, or throws. */
    @FunctionalInterface
    interface StatusFetcher {
        int status(String url) throws Exception;
    }

    private final String pypiBase;
    private final String npmBase;
    private final String mavenBase;
    private final StatusFetcher fetcher;

    public HttpRegistryClient(String pypiBase, String npmBase, String mavenBase, Duration timeout) {
        this(pypiBase, npmBase, mavenBase, defaultFetcher(timeout));
    }

    HttpRegistryClient(String pypiBase, String npmBase, String mavenBase, StatusFetcher fetcher) {
        this.pypiBase = withSlash(pypiBase, DEFAULT_PYPI);
        this.npmBase = withSlash(npmBase, DEFAULT_NPM);
        this.mavenBase = withSlash(mavenBase, DEFAULT_MAVEN);
        this.fetcher = fetcher;
    }

    @Override
    public Optional<Boolean> exists(String ecosystem, String name) {
        String url = urlFor(ecosystem, name);
        if (url == null) {
            return Optional.empty();
        }
        try {
            int status = fetcher.status(url);
            if (status == 200) {
                return Optional.of(true);
            }
            if (status == 404) {
                return Optional.of(false);
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    String urlFor(String ecosystem, String name) {
        switch (ecosystem) {
            case "pypi":
                return pypiBase + normalizePypi(name) + "/";
            case "npm":
                return npmBase + encodeNpm(name);
            case "maven": {
                // name = groupId:artifactId
                int i = name.indexOf(':');
                if (i <= 0 || i == name.length() - 1) {
                    return null;
                }
                String group = name.substring(0, i).replace('.', '/');
                return mavenBase + group + "/" + name.substring(i + 1) + "/maven-metadata.xml";
            }
            default:
                return null;
        }
    }

    /** PEP 503 normalization: lowercase, runs of {@code -_.} collapse to a single {@code -}. */
    static String normalizePypi(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    static String encodeNpm(String name) {
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            if (slash > 0) {
                return name.substring(0, slash) + "%2F" + URLEncoder.encode(name.substring(slash + 1), StandardCharsets.UTF_8);
            }
        }
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }

    private static String withSlash(String v, String dflt) {
        String s = (v == null || v.isBlank()) ? dflt : v.trim();
        return s.endsWith("/") ? s : s + "/";
    }

    static StatusFetcher defaultFetcher(Duration timeout) {
        Duration t = timeout == null ? Duration.ofSeconds(5) : timeout;
        HttpClient client = HttpClient.newBuilder().connectTimeout(t).followRedirects(HttpClient.Redirect.NORMAL).build();
        Function<String, HttpRequest> req = url -> HttpRequest.newBuilder(URI.create(url)).timeout(t)
                .header("User-Agent", "aiv-integrity-gate package-existence").GET().build();
        return url -> client.send(req.apply(url), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
