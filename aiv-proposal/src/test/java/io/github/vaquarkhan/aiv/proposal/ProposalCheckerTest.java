/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.util.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProposalCheckerTest {

    static ProposalDocument kip(String title, String storage, String version) {
        return Sources.confluenceDoc("https://cwiki.example/confluence",
                JsonLite.parse(ProposalFixtures.page("1", title, Integer.parseInt(version), storage)));
    }

    static Set<String> rules(ProposalChecker.Result r) {
        return r.findings().stream().map(Finding::getRuleId).collect(Collectors.toSet());
    }

    @Test
    void completeAcceptedKipIsClean() throws IOException {
        ProposalTemplate t = ProposalTemplate.shipped("kip");
        Map<Integer, List<String>> index = Map.of(899, List.of("KIP-899: x"), 1102, List.of("KIP-1102: y"));
        ProposalChecker.Result r = ProposalChecker.check(kip("KIP-1102: Rebootstrap", ProposalFixtures.GOOD_KIP, "9"), t, index, List.of());
        assertEquals(List.of(), r.findings(), r.findings().stream().map(Finding::getMessage).toList().toString());
        assertEquals("accepted", r.state());
        assertTrue(r.accepted());
        assertEquals(1102, r.number());
        assertEquals(64, r.contentSha256().length());
    }

    @Test
    void templateCopyIsReportedSectionBySection() throws IOException {
        ProposalTemplate t = ProposalTemplate.shipped("kip");
        Map<Integer, List<String>> index = Map.of(899, List.of("KIP-899: x"), 1200, List.of("KIP-1200: a", "KIP-1200: b"));
        ProposalChecker.Result r = ProposalChecker.check(kip("KIP-1200: Faster things", ProposalFixtures.SLOP_KIP, "2"), t, index, List.of());
        assertEquals(Set.of("proposal.template-text", "proposal.placeholder", "proposal.empty-section", "proposal.missing-section",
                "proposal.unknown-state", "proposal.number-collision", "proposal.unknown-reference"), rules(r));
        List<String> msgs = r.findings().stream().map(Finding::getMessage).toList();
        assertTrue(msgs.contains("Required section 'compatibility' not found"), msgs.toString());
        assertTrue(msgs.contains("Required section 'rejected-alternatives' not found"));
        assertTrue(msgs.contains("Section 'Public Interfaces' contains placeholder 'TODO'"));
        assertTrue(msgs.contains("References proposal number 424242, which no page in the index uses"));
        assertTrue(msgs.contains("Number 1200 is used by 2 pages: KIP-1200: a | KIP-1200: b"));
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("Section 'Motivation' still contains the template instruction \"describe the problems")));
        assertTrue(msgs.contains("Section 'Public Interfaces' still contains the template instruction "
                + "\"briefly list any new interfaces that will be introduced as part of thi...\""), msgs.toString());
        assertTrue(msgs.contains("Section 'Motivation' has 0 words of its own (minimum 10)"));
        assertFalse(r.accepted());
        assertEquals("proposal.template-text", r.findings().get(0).getRuleId());
        assertEquals("https://cwiki.example/confluence/spaces/KAFKA/pages/1", r.findings().get(0).getFilePath());
    }

    @Test
    void acceptedWithoutThreadAndChangedAfterAcceptance() throws IOException {
        ProposalTemplate t = ProposalTemplate.shipped("kip");
        ProposalChecker.Result r = ProposalChecker.check(kip("KIP-7: x", ProposalFixtures.ACCEPTED_NO_THREAD, "5"), t, null, List.of(
                Map.of("accepted", false, "content_sha256", "zz"),
                Map.of("accepted", true, "content_sha256", "0000", "version", "3", "ts", "2026-09-01T00:00:00Z"),
                Map.of("accepted", true, "content_sha256", "1111", "version", "4", "ts", "2026-09-02T00:00:00Z")));
        assertEquals(Set.of("proposal.accepted-without-thread", "proposal.changed-after-acceptance"), rules(r));
        assertTrue(r.findings().stream().anyMatch(f -> f.getMessage().equals(
                "Content (status section excluded) changed after it was first recorded as accepted at version 3 (2026-09-01T00:00:00Z); now version 5")));
        ProposalDocument adopted = kip("KIP-7: x", ProposalFixtures.GOOD_KIP.replace("Accepted", "Adopted"), "1");
        ProposalChecker.Result same = ProposalChecker.check(kip("KIP-7: x", ProposalFixtures.GOOD_KIP, "5"), t, null,
                List.of(Map.of("accepted", true, "content_sha256",
                        ProposalChecker.contentSha256(adopted, ProposalChecker.status(adopted, t)))));
        assertEquals(List.of(), same.findings(), "a state edit in the status section does not count as a content change");
        Map<String, Object> noSha = new java.util.HashMap<>();
        noSha.put("accepted", true);
        assertTrue(ProposalChecker.check(kip("KIP-7: x", ProposalFixtures.GOOD_KIP, "5"), t, null, List.of(noSha)).findings().isEmpty());
    }

    @Test
    void stateMissingAndIssueRequirement() {
        ProposalTemplate t = ProposalTemplate.parse(String.join("\n",
                "name: demo",
                "number: \"\\\\bDIP-(\\\\d+)\\\\b\"",
                "state_field: \"^state:\\\\s*(.*)$\"",
                "known_states: [accepted]",
                "accepted_states: [accepted]",
                "discussion_link: \"lists\\\\.apache\\\\.org\"",
                "issue_link: \"DEMO-\\\\d+\"",
                "require_issue_when_accepted: true"));
        assertEquals("DEMO", t.titleWord());
        ProposalDocument noState = new ProposalDocument("file", "f", "DIP-1 x", "", "1", "",
                List.of(new ProposalDocument.Section("", 0, "intro", List.of())), List.of());
        ProposalChecker.Result r = ProposalChecker.check(noState, t, Map.of(1, List.of("DIP-1 x")), List.of());
        assertEquals(Set.of("proposal.missing-state"), rules(r));
        assertEquals("f", r.findings().get(0).getFilePath());
        assertTrue(r.findings().get(0).getMessage().contains("state field"));

        ProposalDocument acc = new ProposalDocument("file", "f", "untitled", null, "1", "",
                List.of(new ProposalDocument.Section("", 0, "state: Accepted\nsee https://lists.apache.org/thread/1", List.of())), List.of());
        assertEquals(Set.of("proposal.accepted-without-issue"), rules(ProposalChecker.check(acc, t, Map.of(), List.of())));
        ProposalDocument accIssue = new ProposalDocument("file", "f", "x", null, "1", "",
                List.of(new ProposalDocument.Section("", 0, "state: Accepted", List.of("https://lists.apache.org/x", "DEMO-4"))), List.of());
        assertTrue(ProposalChecker.check(accIssue, t, null, List.of()).findings().isEmpty());
        ProposalDocument accIssueText = new ProposalDocument("file", "f", "x DEMO-9", null, "1", "",
                List.of(new ProposalDocument.Section("", 0, "state: Accepted", List.of("https://lists.apache.org/x"))), List.of());
        assertTrue(ProposalChecker.check(accIssueText, t, null, List.of()).findings().isEmpty());
        ProposalDocument nullTitle = new ProposalDocument("file", "f", null, null, "1", "",
                List.of(new ProposalDocument.Section("", 0, "state: ", List.of())), List.of());
        ProposalChecker.Result blank = ProposalChecker.check(nullTitle, t, Map.of(), List.of());
        assertEquals(Set.of("proposal.missing-state"), rules(blank));
        assertNull(blank.number());
        ProposalDocument odd = new ProposalDocument("file", "f", "DIP-2 replaces DIP-3", null, "1", "",
                List.of(new ProposalDocument.Section("", 0, "state: 42 parked", List.of())), List.of());
        assertEquals(Set.of("proposal.unknown-state", "proposal.unknown-reference"), rules(ProposalChecker.check(odd, t, Map.of(1, List.of("a")), List.of())));
    }

    @Test
    void flipUsesLabelsAndSpipReportsExternalDocs() throws Exception {
        ProposalTemplate flip = ProposalTemplate.shipped("flip");
        ProposalDocument released = Sources.confluenceDoc("https://c", JsonLite.parse(ProposalFixtures.page("2", "FLIP-9: x", 1,
                ProposalFixtures.GOOD_KIP.replace("<h1>Status</h1>", "<h1>Other</h1>"), "flink", "Released")));
        ProposalChecker.Result r = ProposalChecker.check(released, flip, null, List.of());
        assertEquals("released", r.state());
        assertTrue(r.accepted());
        assertEquals(Set.of(), rules(r));
        ProposalDocument unlabeled = Sources.confluenceDoc("https://c", JsonLite.parse(ProposalFixtures.page("3", "FLIP-10: y", 1,
                ProposalFixtures.GOOD_KIP)));
        ProposalChecker.Result u = ProposalChecker.check(unlabeled, flip, null, List.of());
        assertEquals(Set.of(), rules(u), "Flink does not label state consistently; the FLIP template keeps quiet");
        ProposalTemplate labelled = ProposalTemplate.parse("name: lab\nstate_source: labels\nknown_states: [accepted]");
        ProposalChecker.Result lu = ProposalChecker.check(unlabeled, labelled, null, List.of());
        assertEquals(Set.of("proposal.missing-state"), rules(lu));
        assertTrue(lu.findings().get(0).getMessage().contains("page label"));

        ProposalTemplate spip = ProposalTemplate.shipped("spip");
        assertNull(spip.number());
        ProposalDocument jira = Sources.jira(url -> new Sources.Response(200, ProposalFixtures.SPIP_JIRA), "https://j",
                "SPARK-1", spip.headingLine());
        ProposalChecker.Result s = ProposalChecker.check(jira, spip, Map.of(), List.of());
        assertEquals(Set.of("proposal.missing-section", "proposal.empty-section", "proposal.external-doc"), rules(s));
        assertTrue(s.findings().stream().anyMatch(f -> f.getMessage().equals(
                "Required section 'q3-current-practice' not found (content may be in the linked external document)")));
        assertNull(s.state());
        ProposalDocument textOnlyDoc = new ProposalDocument("jira", "k", "t", "u", "1", "",
                List.of(new ProposalDocument.Section("", 0, "see https://docs.google.com/document/d/x", List.of())), List.of());
        assertTrue(rules(ProposalChecker.check(textOnlyDoc, spip, null, List.of())).contains("proposal.external-doc"));
    }

    @Test
    void templateParsingValidation() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> ProposalTemplate.parse("- a"));
        assertThrows(IllegalArgumentException.class, () -> ProposalTemplate.parse("x: 1"));
        assertThrows(IllegalArgumentException.class, () -> ProposalTemplate.parse("name: a\nrequired_sections:\n  - {id: x}"));
        assertThrows(IllegalArgumentException.class, () -> ProposalTemplate.shipped("../x"));
        assertThrows(IllegalArgumentException.class, () -> ProposalTemplate.shipped("nope"));
        ProposalTemplate t = ProposalTemplate.parse("name: a\ntitle_word: ABC\nrequired_sections:\n  - {id: x, heading: \"^x\"}\nstate_source: none");
        assertEquals(10, t.required().get(0).minWords());
        assertEquals("ABC", t.titleWord());
        assertEquals("a b c", ProposalTemplate.normalize(" A, b!! (c) "));
        ProposalDocument d = new ProposalDocument("file", "f", "t", "u", "1", "",
                List.of(new ProposalDocument.Section("X", 1, "", List.of())), List.of());
        ProposalChecker.Result r = ProposalChecker.check(d, t, null, List.of());
        assertEquals("Section 'X' has 0 words of its own (minimum 10)", r.findings().get(0).getMessage());
        assertNull(ProposalChecker.state(d, ProposalTemplate.parse("name: b\nstate_source: field"), null));
    }

    @Test
    void numberedHeadingsMarkdownMacrosAndAssistantArtifacts() throws IOException {
        ProposalTemplate t = ProposalTemplate.shipped("kip");
        String numbered = ProposalFixtures.GOOD_KIP
                .replace("<h1>Motivation</h1>", "<h1>1 Motivation</h1>")
                .replace("<h1>Proposed Changes</h1>", "<h1>3. Detailed design</h1>")
                .replace("<h1>Test Plan</h1><p>Unit tests and system tests for both rebootstrap triggers.</p>",
                        "<h1>4) Testing</h1><p>Unit tests.</p><p>Thinking...</p>");
        ProposalChecker.Result r = ProposalChecker.check(kip("KIP-5: n", numbered, "1"), t, null, List.of());
        assertEquals(Set.of("proposal.assistant-artifact"), rules(r), r.findings().stream().map(Finding::getMessage).toList().toString());
        assertEquals("Text left by a chat assistant: \"Thinking...\"", r.findings().get(0).getMessage());
        assertEquals("Public Interfaces", ProposalChecker.bare("2.1 Public Interfaces"));
        assertEquals("Test plan", ProposalChecker.bare(" 3) Test plan"));

        String macro = "<h1>Status</h1><p>Current state: Under Discussion</p>"
                + "<ac:structured-macro ac:name=\"markdown\" ac:schema-version=\"1\"><ac:plain-text-body><![CDATA[## 1. Motivation\n"
                + "Deduplication is one of the most common transformations users ask Flink SQL to perform & want <fast>.\n\n"
                + "## 2. Public Interfaces\nA new built-in PTF named DEDUPLICATE_KEEP_FIRST.\n## 3. Proposed Changes\n"
                + "The planner rewrites the call into the existing rank operator with a limit of one per key, so no new runtime"
                + " operator is needed and state size stays the same.\n## 4. Compatibility\nNo change for existing jobs at all.\n"
                + "## 5. Test Plan\nPlanner tests.\n## 6. Rejected Alternatives\nNone.\n]]></ac:plain-text-body></ac:structured-macro>";
        List<ProposalDocument.Section> sections = Markup.html(macro);
        assertEquals(List.of("Status", "1. Motivation", "2. Public Interfaces", "3. Proposed Changes", "4. Compatibility",
                "5. Test Plan", "6. Rejected Alternatives"), sections.stream().map(ProposalDocument.Section::heading).toList());
        assertTrue(sections.get(1).text().endsWith("& want <fast>."), sections.get(1).text());
        assertEquals(List.of(), ProposalChecker.check(kip("KIP-6: m", macro, "1"), t, null, List.of()).findings());

        // Heading variants seen in the October 2026 pilot: bold paragraphs used as headings, "New or Changed
        // Public Interfaces", "Rejected and Deferred Alternatives". None of them is a missing section.
        String variants = ProposalFixtures.GOOD_KIP
                .replace("<h1>Public Interfaces</h1>", "<h1>New or Changed Public Interfaces</h1>")
                .replace("<h1>Rejected Alternatives</h1><h2>Use higher reconnect backoff</h2>",
                        "<p><strong>Rejected and Deferred Alternatives</strong>:</p><p>Use higher reconnect backoff.</p>");
        assertEquals(List.of(), ProposalChecker.check(kip("KIP-8: v", variants, "1"), t, null, List.of()).findings());
        assertEquals("<h4>Goals</h4><p><strong>Current state</strong>: x</p>",
                Markup.promoteBoldParagraphs("<p><b>Goals</b></p><p><strong>Current state</strong>: x</p>"));
        assertEquals("<h4>Rejected Alternatives</h4><p><span style=\"font-size: 12.0px;\">small</span></p>",
                Markup.promoteBoldParagraphs("<p><span style=\"font-size: 24.0px;letter-spacing: -0.01em;\">Rejected Alternatives</span></p>"
                        + "<p><span style=\"font-size: 12.0px;\">small</span></p>"));
    }

    @Test
    void contentHashIgnoresWhitespaceAndCaseButNotWords() {
        ProposalDocument a = new ProposalDocument("f", "1", "t", "u", "1", "",
                List.of(new ProposalDocument.Section("Motivation", 1, "Hello   World", List.of())), List.of());
        ProposalDocument b = new ProposalDocument("f", "1", "t", "u", "2", "",
                List.of(new ProposalDocument.Section("motivation", 1, "hello world", List.of())), List.of());
        ProposalDocument c = new ProposalDocument("f", "1", "t", "u", "3", "",
                List.of(new ProposalDocument.Section("motivation", 1, "hello there", List.of())), List.of());
        assertEquals(ProposalChecker.contentSha256(a, null), ProposalChecker.contentSha256(b, null));
        assertNotEquals(ProposalChecker.contentSha256(a, null), ProposalChecker.contentSha256(c, null));
        assertTrue(Pattern.compile("x").matcher(a.fullText() + "x").find());
        assertEquals("\nh\nb\n", new ProposalDocument("f", "1", null, "u", "1", "",
                List.of(new ProposalDocument.Section("h", 1, "b", List.of())), List.of()).fullText());
    }
}
