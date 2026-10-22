/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import java.util.List;

/**
 * A proposal normalised from any source (Confluence page, JIRA ticket, Markdown or HTML file).
 *
 * @param source       {@code confluence}, {@code jira} or {@code file}
 * @param id           stable source id (page id, issue key, file path)
 * @param title        page title or issue summary
 * @param url          where a human reads it
 * @param version      source revision (Confluence version number, JIRA updated timestamp, file mtime)
 * @param lastModified ISO timestamp of that revision when known, else empty
 * @param sections     headed sections in document order; text before the first heading is a section with an empty heading
 * @param labels       page labels (Confluence), issue labels (JIRA); empty for files
 */
public record ProposalDocument(String source, String id, String title, String url, String version, String lastModified,
                               List<Section> sections, List<String> labels) {

    /**
     * @param heading plain-text heading (empty for the preamble)
     * @param level   heading level, 1 for top level, 0 for the preamble
     * @param text    plain text with one line per paragraph, list item or table row
     * @param links   link targets in the section: hrefs, plus {@code page:<title>} for wiki page links
     */
    public record Section(String heading, int level, String text, List<String> links) {
    }

    /** Whole-document plain text, headings included. */
    public String fullText() {
        StringBuilder b = new StringBuilder(title == null ? "" : title).append('\n');
        for (Section s : sections) {
            b.append(s.heading()).append('\n').append(s.text()).append('\n');
        }
        return b.toString();
    }
}
