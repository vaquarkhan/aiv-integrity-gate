/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits proposal markup into headed sections. Three dialects: HTML / Confluence storage format (XHTML with
 * {@code ac:} and {@code ri:} elements), Markdown ({@code #} headings) and JIRA wiki markup ({@code h1.}
 * headings). Plain-text sources can also promote lines matching a template regex to headings (SPIP's
 * "Q1. What are you trying to do?").
 *
 * <p>Deliberately small and dependency-free: it does not render, it only needs headings, paragraph-level
 * line breaks and link targets.
 */
final class Markup {

    private static final Pattern HTML_HEADING = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");
    private static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern RI_URL = Pattern.compile("(?i)<ri:url\\s+ri:value\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern RI_PAGE = Pattern.compile("(?i)<ri:page\\b[^>]*\\bri:content-title\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(p|li|tr|div|h[1-6]|pre|blockquote|td|th)>|<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern MD_LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)|<(https?://[^>\\s]+)>|(?<![(\\[<\"])\\bhttps?://[^\\s)\\]>\"]+");
    private static final Pattern JIRA_HEADING = Pattern.compile("^h([1-6])\\.\\s*(.*)$");
    private static final Pattern JIRA_LINK = Pattern.compile("\\[[^|\\]]*\\|([^\\]]+)]|(?<![|\\[])\\bhttps?://[^\\s\\]|]+");

    private Markup() {
    }

    private static final Pattern MARKDOWN_MACRO = Pattern.compile(
            "(?is)<ac:structured-macro\\b[^>]*ac:name=\"markdown\"[^>]*>.*?<ac:plain-text-body>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</ac:plain-text-body>.*?</ac:structured-macro>");

    /**
     * Confluence pages can embed Markdown in a {@code markdown} macro (Flink does this). Its body is CDATA, so
     * its headings are invisible to the HTML pass; rewrite it as HTML headings and paragraphs first.
     */
    static String inlineMarkdownMacros(String src) {
        Matcher m = MARKDOWN_MACRO.matcher(src);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            StringBuilder h = new StringBuilder();
            for (String line : m.group(1).replace("\r", "").split("\n")) {
                Matcher hm = MD_HEADING.matcher(line.trim());
                String esc = line.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                if (hm.matches()) {
                    int lvl = hm.group(1).length();
                    h.append("<h").append(lvl).append('>').append(hm.group(2).replace("&", "&amp;").replace("<", "&lt;"))
                            .append("</h").append(lvl).append('>');
                } else if (!esc.isEmpty()) {
                    h.append("<p>").append(esc).append("</p>");
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(h.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A paragraph that is nothing but bold text (optionally ending in a colon) is used as a heading on many wiki pages. */
    private static final Pattern BOLD_PARAGRAPH = Pattern.compile(
            "(?is)<p[^>]*>\\s*<(strong|b)>([^<]{2,80})</\\1>\\s*:?\\s*</p>");

    /** A paragraph that is only a span with a heading-sized font (18px or more): a heading drawn by hand in the editor. */
    private static final Pattern LARGE_SPAN_PARAGRAPH = Pattern.compile(
            "(?is)<p[^>]*>\\s*<span\\s+style=\"[^\"]*font-size:\\s*(?:1[89]|[2-9]\\d)(?:\\.\\d+)?px[^\"]*\">([^<]{2,80})</span>\\s*</p>");

    static String promoteBoldParagraphs(String src) {
        String s = BOLD_PARAGRAPH.matcher(src).replaceAll("<h4>$2</h4>");
        return LARGE_SPAN_PARAGRAPH.matcher(s).replaceAll("<h4>$1</h4>");
    }

    static List<ProposalDocument.Section> html(String markup) {
        String src = promoteBoldParagraphs(inlineMarkdownMacros(markup == null ? "" : markup));
        List<ProposalDocument.Section> out = new ArrayList<>();
        Matcher m = HTML_HEADING.matcher(src);
        int pos = 0;
        String heading = "";
        int level = 0;
        while (m.find()) {
            addHtml(out, heading, level, src.substring(pos, m.start()));
            heading = text(m.group(2)).replace('\n', ' ').trim();
            level = Integer.parseInt(m.group(1));
            pos = m.end();
        }
        addHtml(out, heading, level, src.substring(pos));
        return out;
    }

    private static void addHtml(List<ProposalDocument.Section> out, String heading, int level, String body) {
        if (level == 0 && text(body).isBlank()) {
            return;
        }
        List<String> links = new ArrayList<>();
        for (Pattern p : List.of(HREF, RI_URL)) {
            Matcher m = p.matcher(body);
            while (m.find()) {
                links.add(entities(m.group(1)));
            }
        }
        Matcher pm = RI_PAGE.matcher(body);
        while (pm.find()) {
            links.add("page:" + entities(pm.group(1)));
        }
        out.add(new ProposalDocument.Section(heading, level, text(body), links));
    }

    /** HTML fragment to plain text: block ends become newlines, tags dropped, entities decoded, blank lines removed. */
    static String text(String fragment) {
        String t = BLOCK_END.matcher(fragment).replaceAll("\n");
        t = TAG.matcher(t).replaceAll(" ");
        t = entities(t);
        StringBuilder b = new StringBuilder();
        for (String line : t.split("\n")) {
            String l = line.replace('\u00a0', ' ').replaceAll("[ \\t]+", " ").trim();
            if (!l.isEmpty()) {
                b.append(l).append('\n');
            }
        }
        return b.toString().trim();
    }

    static String entities(String s) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int semi = c == '&' ? s.indexOf(';', i) : -1;
            if (semi > i && semi - i <= 10) {
                String name = s.substring(i + 1, semi);
                String rep = entity(name);
                if (rep != null) {
                    b.append(rep);
                    i = semi + 1;
                    continue;
                }
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }

    private static String entity(String name) {
        switch (name) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "rsquo": case "lsquo": return "'";
            case "rdquo": case "ldquo": return "\"";
            case "ndash": case "mdash": return "-";
            default:
                if (name.matches("#[0-9]{1,7}")) {
                    return new String(Character.toChars(Integer.parseInt(name.substring(1))));
                }
                if (name.matches("#[xX][0-9a-fA-F]{1,6}")) {
                    return new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
                }
                return null;
        }
    }

    static List<ProposalDocument.Section> markdown(String md) {
        return lines(md, line -> {
            Matcher m = MD_HEADING.matcher(line);
            return m.matches() ? new String[] {String.valueOf(m.group(1).length()), m.group(2)} : null;
        }, MD_LINK);
    }

    /** JIRA wiki markup; {@code headingLine} (may be null) promotes matching plain lines to level-2 headings. */
    static List<ProposalDocument.Section> jira(String wiki, Pattern headingLine) {
        return lines(wiki, line -> {
            Matcher m = JIRA_HEADING.matcher(line);
            if (m.matches()) {
                return new String[] {m.group(1), stripWiki(m.group(2))};
            }
            String plain = stripWiki(line);
            if (headingLine != null && headingLine.matcher(plain).find()) {
                return new String[] {"2", plain};
            }
            return null;
        }, JIRA_LINK);
    }

    private static String stripWiki(String s) {
        return s.replaceAll("[*_+]", "").trim();
    }

    private interface HeadingTest {
        String[] test(String line);
    }

    private static List<ProposalDocument.Section> lines(String src, HeadingTest test, Pattern link) {
        List<ProposalDocument.Section> out = new ArrayList<>();
        String heading = "";
        int level = 0;
        StringBuilder body = new StringBuilder();
        for (String raw : (src == null ? "" : src).replace("\r", "").split("\n")) {
            String[] h = test.test(raw.trim());
            if (h != null) {
                addLines(out, heading, level, body.toString(), link);
                heading = h[1].trim();
                level = Integer.parseInt(h[0]);
                body.setLength(0);
            } else {
                body.append(raw).append('\n');
            }
        }
        addLines(out, heading, level, body.toString(), link);
        return out;
    }

    private static void addLines(List<ProposalDocument.Section> out, String heading, int level, String body, Pattern link) {
        StringBuilder t = new StringBuilder();
        for (String l : body.split("\n")) {
            String s = l.trim();
            if (!s.isEmpty()) {
                t.append(s).append('\n');
            }
        }
        String text = t.toString().trim();
        if (level == 0 && text.isEmpty()) {
            return;
        }
        List<String> links = new ArrayList<>();
        Matcher m = link.matcher(body);
        while (m.find()) {
            String g = null;
            for (int i = 1; i <= m.groupCount() && g == null; i++) {
                g = m.group(i);
            }
            links.add(g == null ? m.group() : g);
        }
        out.add(new ProposalDocument.Section(heading, level, text, links));
    }
}
