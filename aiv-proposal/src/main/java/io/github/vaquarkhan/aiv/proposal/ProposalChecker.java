/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.util.JsonLite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;

/**
 * Deterministic, advisory checks over one proposal. Every finding has a stable rule id
 * ({@code proposal.<rule>}) and names the section it concerns. Nothing here judges whether the idea is good;
 * it reports what a shepherd would otherwise find by reading: missing or empty sections, the template's own
 * instructions left in place, placeholders, an accepted proposal with no discussion thread, numbers that
 * collide or do not exist, and content that changed after acceptance.
 */
final class ProposalChecker {

    /** Outcome of a check: findings plus the facts a report needs. */
    record Result(ProposalDocument doc, String state, boolean accepted, Integer number, String contentSha256,
                  List<Finding> findings) {
    }

    private ProposalChecker() {
    }

    /**
     * @param index   proposal number to titles of every page using it, or null when no index was loaded
     * @param history prior {@code aiv-proposal/1} audit records for this document, oldest first (may be empty)
     */
    static Result check(ProposalDocument doc, ProposalTemplate t, Map<Integer, List<String>> index,
                        List<Map<String, Object>> history) {
        List<Finding> out = new ArrayList<>();
        String where = doc.url() == null || doc.url().isBlank() ? doc.id() : doc.url();

        boolean externalDoc = false;
        if (t.externalDoc() != null) {
            for (ProposalDocument.Section s : doc.sections()) {
                for (String l : s.links()) {
                    if (t.externalDoc().matcher(l).find()) {
                        externalDoc = true;
                    }
                }
                if (t.externalDoc().matcher(s.text()).find()) {
                    externalDoc = true;
                }
            }
        }

        for (ProposalTemplate.Required r : t.required()) {
            ProposalDocument.Section s = find(doc, r);
            if (s == null) {
                out.add(f("missing-section", where, "Required section '" + r.id() + "' not found"
                        + (externalDoc ? " (content may be in the linked external document)" : "")));
                continue;
            }
            String body = body(doc, s);
            String norm = ProposalTemplate.normalize(body);
            for (String phrase : t.templatePhrases()) {
                if (!phrase.isEmpty() && norm.contains(phrase)) {
                    out.add(f("template-text", where, "Section '" + s.heading() + "' still contains the template instruction \""
                            + abbreviate(phrase) + "\""));
                    norm = norm.replace(phrase, " ");
                }
            }
            if (t.placeholder() != null) {
                Matcher pm = t.placeholder().matcher(body);
                if (pm.find()) {
                    out.add(f("placeholder", where, "Section '" + s.heading() + "' contains placeholder '" + pm.group() + "'"));
                }
            }
            int words = norm.isBlank() ? 0 : norm.trim().split("\\s+").length;
            if (words < r.minWords()) {
                out.add(f("empty-section", where, "Section '" + s.heading() + "' has " + words + " words of its own (minimum "
                        + r.minWords() + ")"));
            }
        }
        if (externalDoc) {
            out.add(f("external-doc", where, "Proposal content links to an external document that AIV cannot read and that is not"
                    + " archived on ASF infrastructure; reviewers must open it separately"));
        }

        ProposalDocument.Section status = status(doc, t);
        String state = state(doc, t, status);
        boolean accepted = state != null && startsWithAny(state, t.acceptedStates());
        String full = doc.fullText();
        for (java.util.regex.Pattern a : t.assistantArtifacts()) {
            Matcher am = a.matcher(full);
            if (am.find()) {
                out.add(f("assistant-artifact", where, "Text left by a chat assistant: \"" + am.group().trim() + "\""));
            }
        }
        if (!"none".equals(t.stateSource())) {
            if (state == null || state.isBlank()) {
                // Some projects do not record state consistently (Flink labels); their template turns this off.
                if (t.reportMissingState()) {
                    out.add(f("missing-state", where, "No proposal state found ("
                            + ("labels".equals(t.stateSource()) ? "expected a page label" : "expected a state field in the status section") + ")"));
                }
            } else if (!startsWithAny(state, t.knownStates())) {
                out.add(f("unknown-state", where, "State '" + state + "' is not one of the project's states"));
            }
        }
        if (accepted && t.discussionLink() != null && !anyLink(status == null ? doc.sections() : List.of(status), t.discussionLink())) {
            out.add(f("accepted-without-thread", where, "State is '" + state + "' but the status section links no mailing-list"
                    + " discussion or vote thread"));
        }
        if (accepted && t.requireIssueWhenAccepted() && t.issueLink() != null
                && !anyLink(doc.sections(), t.issueLink()) && !t.issueLink().matcher(doc.fullText()).find()) {
            out.add(f("accepted-without-issue", where, "State is '" + state + "' but no tracking issue is linked"));
        }

        Integer number = null;
        if (t.number() != null) {
            Matcher nm = t.number().matcher(doc.title() == null ? "" : doc.title());
            if (nm.find()) {
                number = Integer.parseInt(nm.group(1));
            }
        }
        if (index != null && t.number() != null) {
            if (number != null && index.getOrDefault(number, List.of()).size() > 1) {
                out.add(f("number-collision", where, "Number " + number + " is used by " + index.get(number).size()
                        + " pages: " + String.join(" | ", index.get(number))));
            }
            TreeSet<Integer> unknown = new TreeSet<>();
            StringBuilder refs = new StringBuilder(doc.fullText());
            for (ProposalDocument.Section s : doc.sections()) {
                for (String l : s.links()) {
                    refs.append('\n').append(l);
                }
            }
            Matcher rm = t.number().matcher(refs);
            while (rm.find()) {
                int n = Integer.parseInt(rm.group(1));
                if (!index.containsKey(n) && (number == null || n != number)) {
                    unknown.add(n);
                }
            }
            for (int n : unknown) {
                out.add(f("unknown-reference", where, "References proposal number " + n + ", which no page in the index uses"));
            }
        }

        String sha = contentSha256(doc, status);
        if (accepted) {
            for (Map<String, Object> h : history) {
                if (Boolean.TRUE.equals(h.get("accepted"))) {
                    String first = JsonLite.str(h.get("content_sha256"));
                    if (first != null && !first.equals(sha)) {
                        out.add(f("changed-after-acceptance", where, "Content (status section excluded) changed after it was first"
                                + " recorded as accepted at version " + JsonLite.str(h.get("version")) + " ("
                                + JsonLite.str(h.get("ts")) + "); now version " + doc.version()));
                    }
                    break;
                }
            }
        }
        return new Result(doc, state, accepted, number, sha, out);
    }

    /** A section's own text plus its subsections (following sections with a deeper heading level). */
    static String body(ProposalDocument doc, ProposalDocument.Section s) {
        StringBuilder b = new StringBuilder(s.text());
        List<ProposalDocument.Section> all = doc.sections();
        for (int i = all.indexOf(s) + 1; i < all.size() && all.get(i).level() > s.level(); i++) {
            b.append('\n').append(all.get(i).heading()).append('\n').append(all.get(i).text());
        }
        return b.toString();
    }

    /** Heading text without section numbering: "2.1 Public Interfaces" and "3) Test plan" compare as their words. */
    static String bare(String heading) {
        return heading.trim().replaceFirst("^[\\d.\\s)(\\-:]+", "").trim();
    }

    private static ProposalDocument.Section find(ProposalDocument doc, ProposalTemplate.Required r) {
        for (ProposalDocument.Section s : doc.sections()) {
            if (r.heading().matcher(bare(s.heading())).find()) {
                return s;
            }
        }
        return null;
    }

    static ProposalDocument.Section status(ProposalDocument doc, ProposalTemplate t) {
        if (t.statusHeading() == null) {
            return null;
        }
        for (ProposalDocument.Section s : doc.sections()) {
            if (t.statusHeading().matcher(bare(s.heading())).find()) {
                return s;
            }
        }
        return null;
    }

    static String state(ProposalDocument doc, ProposalTemplate t, ProposalDocument.Section status) {
        if ("labels".equals(t.stateSource())) {
            for (String l : doc.labels()) {
                String v = l.toLowerCase(Locale.ROOT).trim();
                if (startsWithAny(v, t.knownStates())) {
                    return v;
                }
            }
            return null;
        }
        if (!"field".equals(t.stateSource()) || t.stateField() == null) {
            return null;
        }
        String text = status == null ? doc.fullText() : status.text();
        Matcher m = t.stateField().matcher(text);
        if (!m.find()) {
            return null;
        }
        return m.group(1).replaceAll("[\\[\\]\"*_]", "").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        String v = value.toLowerCase(Locale.ROOT).replaceAll("^[^a-z]+", "");
        for (String p : prefixes) {
            if (v.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyLink(List<ProposalDocument.Section> sections, java.util.regex.Pattern p) {
        for (ProposalDocument.Section s : sections) {
            for (String l : s.links()) {
                if (p.matcher(l).find()) {
                    return true;
                }
            }
            if (p.matcher(s.text()).find()) {
                return true;
            }
        }
        return false;
    }

    /** SHA-256 of the normalised body with the status section excluded, so state and release edits do not count. */
    static String contentSha256(ProposalDocument doc, ProposalDocument.Section status) {
        StringBuilder b = new StringBuilder();
        for (ProposalDocument.Section s : doc.sections()) {
            if (s == status) {
                continue;
            }
            b.append(ProposalTemplate.normalize(s.heading())).append('\n').append(ProposalTemplate.normalize(s.text())).append('\n');
        }
        return io.github.vaquarkhan.aiv.audit.HashChainAudit.sha256Hex(b.toString());
    }

    private static Finding f(String rule, String where, String msg) {
        return Finding.atLine("proposal." + rule, where, 1, msg);
    }

    private static String abbreviate(String s) {
        return s.length() > 70 ? s.substring(0, 70) + "..." : s;
    }
}
