/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aiv.plugin.doc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates relative Markdown links {@code [text](target)}: target file must exist under the workspace;
 * if a fragment {@code #anchor} is present and the target is Markdown, a matching heading slug must exist.
 *
 * @author Vaquar Khan
 */
public final class MarkdownLinkChecker {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+?)\\s*$");

    private final Path workspace;

    public MarkdownLinkChecker(Path workspace) {
        this.workspace = workspace;
    }

    /**
     * @return first violation message, or {@code null} if none
     */
    public String validate(String docPath, String content) {
        List<String> all = validateAll(docPath, content);
        return all.isEmpty() ? null : all.get(0);
    }

    public List<String> validateAll(String docPath, String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        Path docDir = parentDir(docPath);
        Matcher m = LINK.matcher(content);
        while (m.find()) {
            String raw = m.group(2).trim();
            if (raw.isEmpty()) {
                continue;
            }
            // Optional title: url "title"
            int spaceQuote = raw.indexOf(' ');
            if (spaceQuote > 0 && raw.charAt(spaceQuote + 1) == '"') {
                raw = raw.substring(0, spaceQuote).trim();
            }
            if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("mailto:")
                    || raw.startsWith("ftp://") || raw.startsWith("//")) {
                continue;
            }

            String pathPart = raw;
            String fragment = null;
            int hash = pathPart.indexOf('#');
            if (hash >= 0) {
                fragment = pathPart.substring(hash + 1).trim();
                pathPart = pathPart.substring(0, hash).trim();
            }
            if (pathPart.isEmpty()) {
                continue;
            }

            Path resolved = docDir.resolve(pathPart.replace("\\", "/")).normalize();
            Path workspaceAbs = workspace.toAbsolutePath().normalize();
            if (!resolved.startsWith(workspaceAbs)) {
                resolved = workspaceAbs.resolve(pathPart.replace("\\", "/")).normalize();
            }
            if (!resolved.startsWith(workspaceAbs)) {
                violations.add(String.format("Markdown link in %s escapes workspace: %s", docPath, pathPart));
                continue;
            }

            if (!Files.isRegularFile(resolved)) {
                violations.add(String.format("Markdown link in %s: target does not exist: %s", docPath, pathPart));
                continue;
            }

            if (fragment != null && !fragment.isEmpty()
                    && pathPart.toLowerCase(Locale.ROOT).endsWith(".md")) {
                String anchorErr = validateAnchor(resolved, fragment, docPath, pathPart);
                if (anchorErr != null) {
                    violations.add(anchorErr);
                }
            }
        }
        return violations;
    }

    private Path parentDir(String docPath) {
        Path p = workspace.resolve(docPath).normalize();
        Path parent = p.getParent();
        return parent != null ? parent : workspace;
    }

    private static String validateAnchor(Path mdFile, String fragment, String docPath, String pathPart) {
        try {
            String body = Files.readString(mdFile);
            if (headingSlugs(body).contains(normalizeFragment(fragment))) {
                return null;
            }
            return String.format(
                    "Markdown link in %s: fragment #%s not found as heading in %s",
                    docPath, fragment, pathPart);
        } catch (Exception e) {
            return String.format("Markdown link in %s: could not read %s for anchor check", docPath, pathPart);
        }
    }

    /** GitHub-style slug: lowercase, alphanumerics and hyphens from heading text. */
    static String slugifyHeading(String heading) {
        String s = heading.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9\\s-]", "");
        s = s.replaceAll("[\\s_]+", "-");
        s = s.replaceAll("-+", "-");
        if (s.startsWith("-")) {
            s = s.substring(1);
        }
        if (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String normalizeFragment(String fragment) {
        return fragment.trim().toLowerCase(Locale.ROOT);
    }

    /** Collect slugs from ATX headings. */
    private static Set<String> headingSlugs(String markdown) {
        Set<String> slugs = new HashSet<>();
        Matcher hm = HEADING.matcher(markdown);
        while (hm.find()) {
            slugs.add(slugifyHeading(hm.group(1)));
        }
        return slugs;
    }
}
