/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Opt-in mechanical cleanup ({@code --fix}): removes only deterministic junk lines —
 * merge-conflict markers and known AI elision placeholders. Off by default; never invents code.
 */
public final class MechanicalFixer {

    private static final Pattern CONFLICT = Pattern.compile("^<<<<<<<|^=======|^>>>>>>>");
    private static final Pattern ELISION = Pattern.compile(
            "(?i)^\\s*(?://|#|--|/\\*)?\\s*\\.\\.\\.\\s*(existing code|rest of (?:the )?code|code unchanged).*");

    private MechanicalFixer() {
    }

    /**
     * @return number of files rewritten
     */
    public static int apply(Path workspace, List<String> relativePaths) throws IOException {
        if (workspace == null || relativePaths == null) {
            return 0;
        }
        int changed = 0;
        for (String rel : relativePaths) {
            if (rel == null || rel.isBlank()) {
                continue;
            }
            Path p = workspace.resolve(rel).normalize();
            if (!p.startsWith(workspace.toAbsolutePath().normalize()) && !p.startsWith(workspace.normalize())) {
                continue;
            }
            if (!Files.isRegularFile(p)) {
                continue;
            }
            String original = Files.readString(p, StandardCharsets.UTF_8);
            String fixed = fixContent(original);
            if (!fixed.equals(original)) {
                Files.writeString(p, fixed, StandardCharsets.UTF_8);
                changed++;
            }
        }
        return changed;
    }

    static String fixContent(String content) {
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        String[] lines = content.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        boolean changed = false;
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (CONFLICT.matcher(trimmed).find()) {
                changed = true;
                continue;
            }
            if (ELISION.matcher(line).matches()) {
                changed = true;
                continue;
            }
            out.add(line);
        }
        if (!changed) {
            return content;
        }
        return String.join("\n", out);
    }
}
