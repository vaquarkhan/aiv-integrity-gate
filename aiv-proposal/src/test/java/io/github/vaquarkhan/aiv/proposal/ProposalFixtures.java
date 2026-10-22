/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

/** Confluence storage-format and JIRA fixtures modelled on real KIP, FLIP and SPIP pages. */
final class ProposalFixtures {

    private ProposalFixtures() {
    }

    /** A complete, accepted KIP that references an existing KIP and links its discussion thread. */
    static final String GOOD_KIP = String.join("",
            "<p><ac:structured-macro ac:name=\"toc\" /></p>",
            "<h1>Status</h1>",
            "<p><strong>Current state</strong>: <em>Accepted</em></p>",
            "<p><strong>Discussion thread</strong>: <a href=\"https://lists.apache.org/thread/abc\">here</a></p>",
            "<p><strong>JIRA</strong>: <a href=\"https://issues.apache.org/jira/browse/KAFKA-17885\">KAFKA-17885</a></p>",
            "<h1>Motivation</h1>",
            "<p><ac:link><ri:page ri:space-key=\"KAFKA\" ri:content-title=\"KIP-899: Allow clients to rebootstrap\" /></ac:link>",
            " added support for clients to rebootstrap when all nodes in the last known metadata are unavailable, and this",
            " proposal extends it with a timeout&nbsp;and an error code.</p>",
            "<h1>Public Interfaces</h1><p>New client configuration <code>metadata.recovery.rebootstrap.trigger.ms</code> with a default of five minutes.</p>",
            "<h1>Proposed Changes</h1><p>Clients rebootstrap when no metadata response arrives within the configured time, or when the",
            " broker returns the new REBOOTSTRAP_REQUIRED error code; both paths reuse the existing bootstrap logic and",
            " are covered by metrics.</p>",
            "<h1>Compatibility, Deprecation, and Migration Plan</h1><p>No impact on existing users because the default keeps behaviour.</p>",
            "<h1>Test Plan</h1><p>Unit tests and system tests for both rebootstrap triggers.</p>",
            "<h1>Rejected Alternatives</h1><h2>Use higher reconnect backoff</h2><p>Rejected because it delays recovery.</p>");

    /** A draft KIP copied from the template and left half-filled. */
    static final String SLOP_KIP = String.join("",
            "<h1>Status</h1>",
            "<p><strong>Current state</strong>: <em>[One of &quot;Under Discussion&quot;, &quot;Accepted&quot;, &quot;Rejected&quot;]</em></p>",
            "<p><strong>Discussion thread</strong>: TBD</p>",
            "<h1>Motivation</h1><p>Describe the problems you are trying to solve.</p>",
            "<h1>Public Interfaces</h1><p>Briefly list any new interfaces that will be introduced as part of this proposal or any",
            " existing interfaces that will be removed or changed. TODO</p>",
            "<h1>Proposed Changes</h1><p>We will improve performance, see KIP-424242 and KIP-899.</p>",
            "<h1>Test Plan</h1><p>Describe in few sentences how the KIP will be tested.</p>");

    /** Accepted but no thread link anywhere in the status section. */
    static final String ACCEPTED_NO_THREAD = GOOD_KIP.replace("<a href=\"https://lists.apache.org/thread/abc\">here</a>", "here");

    static String page(String id, String title, int version, String storage, String... labels) {
        StringBuilder l = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            l.append(i == 0 ? "" : ",").append("{\"name\":\"").append(labels[i]).append("\"}");
        }
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"version\":{\"number\":" + version
                + ",\"when\":\"2026-10-0" + Math.min(9, version) + "T10:00:00.000Z\"},\"_links\":{\"webui\":\"/spaces/KAFKA/pages/" + id
                + "\"},\"metadata\":{\"labels\":{\"results\":[" + l + "]}},\"body\":{\"storage\":{\"value\":"
                + io.github.vaquarkhan.aiv.util.JsonLite.write(storage) + "}}}";
    }

    static final String SPIP_JIRA = io.github.vaquarkhan.aiv.util.JsonLite.write(java.util.Map.of("fields", java.util.Map.of(
            "summary", "[SPIP] Faster shuffle",
            "updated", "2026-10-01T00:00:00.000+0000",
            "labels", java.util.List.of("SPIP"),
            "description", String.join("\n",
                    "h2. Q1. What are you trying to do?",
                    "Make shuffle faster for large *joins* without changing the API at all.",
                    "Design doc: [link|https://docs.google.com/document/d/abc]",
                    "*Q2.* What problem is this proposal NOT designed to solve?",
                    "Streaming."))));
}
