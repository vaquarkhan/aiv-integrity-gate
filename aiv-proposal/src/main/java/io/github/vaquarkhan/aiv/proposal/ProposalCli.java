/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import io.github.vaquarkhan.aiv.audit.HashChainAudit;
import io.github.vaquarkhan.aiv.audit.VerifyResult;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code aiv proposal check|scan}. Advisory by design: findings never change the exit code unless
 * {@code --fail-on-findings} is passed. Exit codes: 0 ran, 1 findings with {@code --fail-on-findings},
 * 2 usage or configuration error (including a broken audit chain), 3 a source could not be read.
 */
public final class ProposalCli {

    static final String USAGE = String.join("\n",
            "Usage:",
            "  aiv proposal check (--template kip|flip|spip | --template-file F)",
            "      (--file F | --confluence-url URL | --space S --title T | --jira KEY)",
            "      [--confluence-base URL] [--jira-base URL] [--index] [--audit-log F] [--output-json F] [--fail-on-findings]",
            "  aiv proposal scan (--template ... ) --space S --last N --out DIR",
            "      [--confluence-base URL] [--index] [--audit-log F] [--fail-on-findings]",
            "Defaults: --confluence-base https://cwiki.apache.org/confluence, --jira-base https://issues.apache.org/jira");

    private ProposalCli() {
    }

    public static int run(String[] args) {
        return run(args, System.out, System.err, Sources.defaultHttp(Duration.ofSeconds(30)), Clock.systemUTC());
    }

    static int run(String[] args, PrintStream out, PrintStream err, Sources.Http http, Clock clock) {
        if (args.length == 0 || !("check".equals(args[0]) || "scan".equals(args[0]))) {
            err.println(USAGE);
            return 2;
        }
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                err.println("unexpected argument '" + a + "'\n" + USAGE);
                return 2;
            }
            boolean flag = a.equals("--index") || a.equals("--fail-on-findings") || a.equals("--quiet");
            if (flag) {
                o.put(a, "true");
            } else if (i + 1 < args.length) {
                o.put(a, args[++i]);
            } else {
                err.println("missing value for " + a + "\n" + USAGE);
                return 2;
            }
        }
        try {
            ProposalTemplate t = o.containsKey("--template-file") ? ProposalTemplate.file(Path.of(o.get("--template-file")))
                    : ProposalTemplate.shipped(o.getOrDefault("--template", ""));
            String cbase = o.getOrDefault("--confluence-base", "https://cwiki.apache.org/confluence").replaceAll("/+$", "");
            String jbase = o.getOrDefault("--jira-base", "https://issues.apache.org/jira").replaceAll("/+$", "");
            Path audit = o.containsKey("--audit-log") ? Path.of(o.get("--audit-log")) : null;
            List<Map<String, Object>> history = history(audit);
            boolean quiet = o.containsKey("--quiet");

            List<ProposalDocument> docs = new ArrayList<>();
            String space = o.containsKey("--space") ? o.get("--space")
                    : o.containsKey("--confluence-url") ? spaceOf(o.get("--confluence-url")) : null;
            if (o.containsKey("--index") && (space == null || t.number() == null)) {
                err.println("--index needs a Confluence space (--space or a /display/SPACE/ URL) and a template with a number pattern");
                return 2;
            }
            if ("scan".equals(args[0])) {
                if (space == null || !o.containsKey("--last") || !o.containsKey("--out")) {
                    err.println("scan needs --space, --last and --out\n" + USAGE);
                    return 2;
                }
                if (t.number() == null) {
                    err.println("template '" + t.name() + "' has no proposal number pattern; scan needs one");
                    return 2;
                }
                int n = Integer.parseInt(o.get("--last"));
                for (String id : Sources.confluenceRecent(http, cbase, space, t.titleWord(), t.number(), n)) {
                    docs.add(Sources.confluenceById(http, cbase, id));
                }
            } else if (o.containsKey("--file")) {
                docs.add(Sources.file(Path.of(o.get("--file"))));
            } else if (o.containsKey("--confluence-url")) {
                docs.add(Sources.confluenceUrl(http, o.get("--confluence-url")));
            } else if (space != null && o.containsKey("--title")) {
                docs.add(Sources.confluenceByTitle(http, cbase, space, o.get("--title")));
            } else if (o.containsKey("--jira")) {
                docs.add(Sources.jira(http, jbase, o.get("--jira"), t.headingLine()));
            } else {
                err.println("check needs a source: --file, --confluence-url, --space with --title, or --jira\n" + USAGE);
                return 2;
            }

            Map<Integer, List<String>> index = o.containsKey("--index")
                    ? Sources.confluenceIndex(http, cbase, space, t.titleWord(), t.number(), 50) : null;

            List<ProposalChecker.Result> results = new ArrayList<>();
            for (ProposalDocument d : docs) {
                List<Map<String, Object>> mine = new ArrayList<>();
                for (Map<String, Object> h : history) {
                    if (d.source().equals(h.get("source")) && d.id().equals(h.get("id"))) {
                        mine.add(h);
                    }
                }
                ProposalChecker.Result r = ProposalChecker.check(d, t, index, mine);
                results.add(r);
                if (audit != null) {
                    Map<String, Object> rec = record(r, t, clock);
                    HashChainAudit.append(audit, JsonLite.write(rec));
                    history.add(rec);
                }
                if (!quiet) {
                    out.print(render(r));
                }
            }
            String head = audit == null ? null : HashChainAudit.headHash(audit);
            if (head != null && !quiet) {
                out.println("[AUDIT] " + results.size() + " proposal record(s) appended to "
                        + audit.toString().replace('\\', '/') + "; chain head " + head);
            }
            if (o.containsKey("--output-json")) {
                write(Path.of(o.get("--output-json")), JsonLite.write(json(results, t, head)) + "\n");
            }
            if ("scan".equals(args[0])) {
                Path dir = Path.of(o.get("--out"));
                write(dir.resolve("proposals.json"), JsonLite.write(json(results, t, head)) + "\n");
                write(dir.resolve("SUMMARY.md"), summary(results, t, space, index != null));
                out.println("wrote " + dir.resolve("SUMMARY.md").toString().replace('\\', '/'));
            }
            boolean any = results.stream().anyMatch(r -> !r.findings().isEmpty());
            return any && o.containsKey("--fail-on-findings") ? 1 : 0;
        } catch (IllegalArgumentException e) {
            err.println("aiv proposal: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("aiv proposal: could not read source: " + e.getMessage());
            return 3;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("aiv proposal: interrupted");
            return 3;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> history(Path audit) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (audit == null || !Files.exists(audit)) {
            return out;
        }
        VerifyResult v = HashChainAudit.verify(audit);
        if (!v.isIntact()) {
            throw new IllegalArgumentException("audit chain " + audit + " is broken at record " + v.getFirstBrokenIndex()
                    + "; refusing to compare against tampered history");
        }
        for (String p : HashChainAudit.payloads(audit)) {
            Object o = JsonLite.parse(p);
            if (o instanceof Map<?, ?> m && "aiv-proposal/1".equals(m.get("schema"))) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    static Map<String, Object> record(ProposalChecker.Result r, ProposalTemplate t, Clock clock) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("schema", "aiv-proposal/1");
        rec.put("ts", clock.instant().toString());
        rec.put("template", t.name());
        rec.put("source", r.doc().source());
        rec.put("id", r.doc().id());
        rec.put("title", r.doc().title());
        rec.put("url", r.doc().url());
        rec.put("version", r.doc().version());
        rec.put("state", r.state());
        rec.put("accepted", r.accepted());
        rec.put("number", r.number());
        rec.put("content_sha256", r.contentSha256());
        List<Object> rules = new ArrayList<>();
        List<Object> fs = new ArrayList<>();
        for (Finding f : r.findings()) {
            rules.add(f.getRuleId());
            fs.add(Map.of("rule_id", f.getRuleId(), "message", f.getMessage()));
        }
        rec.put("rules", rules);
        rec.put("findings", fs);
        return rec;
    }

    static String render(ProposalChecker.Result r) {
        StringBuilder b = new StringBuilder();
        b.append("=== ").append(r.doc().title()).append(" ===\n")
                .append(r.doc().url()).append(" (version ").append(r.doc().version()).append(", state ")
                .append(r.state() == null ? "unknown" : r.state()).append(")\n");
        if (r.findings().isEmpty()) {
            b.append("  no findings\n");
        }
        for (Finding f : r.findings()) {
            b.append("  ").append(f.getRuleId()).append(": ").append(f.getMessage()).append('\n');
        }
        return b.toString();
    }

    static Map<String, Object> json(List<ProposalChecker.Result> results, ProposalTemplate t, String head) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "aiv-proposal-report/1");
        root.put("template", t.name());
        root.put("audit_head", head);
        root.put("note", "Advisory, deterministic evidence about the document. It does not judge the design or authorship.");
        List<Object> list = new ArrayList<>();
        for (ProposalChecker.Result r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", r.doc().title());
            m.put("url", r.doc().url());
            m.put("version", r.doc().version());
            m.put("state", r.state());
            m.put("accepted", r.accepted());
            m.put("content_sha256", r.contentSha256());
            List<Object> fs = new ArrayList<>();
            for (Finding f : r.findings()) {
                fs.add(Map.of("rule_id", f.getRuleId(), "message", f.getMessage()));
            }
            m.put("findings", fs);
            list.add(m);
        }
        root.put("proposals", list);
        return root;
    }

    static String summary(List<ProposalChecker.Result> results, ProposalTemplate t, String space, boolean indexed) {
        Map<String, Integer> byRule = new TreeMap<>();
        int withFindings = 0;
        for (ProposalChecker.Result r : results) {
            if (!r.findings().isEmpty()) {
                withFindings++;
            }
            for (String rule : r.findings().stream().map(Finding::getRuleId).distinct().toList()) {
                byRule.merge(rule, 1, Integer::sum);
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("# Proposal scan: ").append(t.name()).append(" in ").append(space).append("\n\n")
                .append("Advisory, deterministic evidence about each document. It does not judge the design, and it does not")
                .append(" determine authorship.").append(indexed ? " Number checks used the space index." : " Number checks were off (no --index).")
                .append("\n\n")
                .append("Proposals scanned: ").append(results.size()).append(". With at least one finding: ").append(withFindings).append(".\n\n")
                .append("| Rule | Proposals |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : byRule.entrySet()) {
            b.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
        }
        b.append("\n| Proposal | State | Version | Findings |\n|---|---|---|---|\n");
        for (ProposalChecker.Result r : results) {
            b.append("| [").append(cell(r.doc().title())).append("](").append(r.doc().url()).append(") | ")
                    .append(r.state() == null ? "unknown" : cell(r.state())).append(" | ").append(r.doc().version()).append(" | ")
                    .append(r.findings().isEmpty() ? "none" : cell(String.join(", ",
                            r.findings().stream().map(Finding::getRuleId).distinct().toList())))
                    .append(" |\n");
        }
        return b.toString();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    static String spaceOf(String url) {
        java.util.regex.Matcher m = Pattern.compile("/(?:display|spaces)/([^/]+)/").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static void write(Path p, String text) throws IOException {
        Path parent = p.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }
}
