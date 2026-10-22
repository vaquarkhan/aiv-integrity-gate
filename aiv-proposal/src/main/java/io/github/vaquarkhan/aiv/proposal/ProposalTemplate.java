/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.proposal;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A project's proposal process as data: which sections a proposal must have, what the template's own
 * instruction text says (so leftovers can be detected), where the state lives and which states mean
 * "accepted". Shipped templates live under {@code proposal-templates/<name>.yaml}; a project can pass its own
 * file with {@code --template-file}.
 */
record ProposalTemplate(String name, String titleWord, Pattern number, List<Required> required, Pattern statusHeading,
                        Pattern stateField, String stateSource, List<String> knownStates, List<String> acceptedStates,
                        Pattern discussionLink, Pattern issueLink, boolean requireIssueWhenAccepted,
                        List<String> templatePhrases, Pattern placeholder, Pattern headingLine, Pattern externalDoc,
                        boolean reportMissingState, List<Pattern> assistantArtifacts) {

    record Required(String id, Pattern heading, int minWords) {
    }

    static ProposalTemplate shipped(String name) throws IOException {
        if (!name.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException("template name must be lowercase letters, digits, - or _");
        }
        try (InputStream in = ProposalTemplate.class.getClassLoader().getResourceAsStream("proposal-templates/" + name + ".yaml")) {
            if (in == null) {
                throw new IllegalArgumentException("unknown proposal template '" + name + "' (shipped: kip, flip, spip)");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static ProposalTemplate file(Path p) throws IOException {
        return parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static ProposalTemplate parse(String yaml) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("proposal template must be a mapping");
        }
        Map<String, Object> t = (Map<String, Object>) m;
        String name = str(t.get("name"));
        if (name == null) {
            throw new IllegalArgumentException("proposal template needs 'name'");
        }
        List<Required> req = new ArrayList<>();
        for (Object o : list(t.get("required_sections"))) {
            Map<String, Object> r = (Map<String, Object>) o;
            String id = str(r.get("id"));
            String heading = str(r.get("heading"));
            if (id == null || heading == null) {
                throw new IllegalArgumentException("required_sections entries need 'id' and 'heading'");
            }
            int min = r.get("min_words") instanceof Number n ? n.intValue() : 10;
            req.add(new Required(id, ci(heading), min));
        }
        String titleWord = str(t.get("title_word")) == null ? name.toUpperCase(Locale.ROOT) : str(t.get("title_word"));
        return new ProposalTemplate(name, titleWord, rx(t.get("number")), req, rx(t.get("status_heading")), rx(t.get("state_field")),
                str(t.get("state_source")) == null ? "field" : str(t.get("state_source")),
                lower(t.get("known_states")), lower(t.get("accepted_states")),
                rx(t.get("discussion_link")), rx(t.get("issue_link")), Boolean.TRUE.equals(t.get("require_issue_when_accepted")),
                normalized(t.get("template_phrases")), rx(t.get("placeholder")), rx(t.get("heading_line")), rx(t.get("external_doc")),
                !Boolean.FALSE.equals(t.get("report_missing_state")), patterns(t.get("assistant_artifacts")));
    }

    private static List<Pattern> patterns(Object v) {
        List<Pattern> out = new ArrayList<>();
        for (Object o : list(v)) {
            out.add(ci(o.toString()));
        }
        return out;
    }

    private static Pattern rx(Object v) {
        return v == null ? null : ci(v.toString());
    }

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    }

    private static String str(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static List<?> list(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    private static List<String> lower(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) {
            out.add(o.toString().trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static List<String> normalized(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) {
            out.add(normalize(o.toString()));
        }
        return out;
    }

    /** Lowercase, punctuation-free, single-spaced: how template phrases and section text are compared. */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
    }
}
