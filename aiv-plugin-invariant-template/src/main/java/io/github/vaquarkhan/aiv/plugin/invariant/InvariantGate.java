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

package io.github.vaquarkhan.aiv.plugin.invariant;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generic invariant checks that should never reach mainline code.
 *
 * @author Vaquar Khan
 */
public final class InvariantGate implements QualityGate {
    private static final Pattern MERGE_CONFLICT_MARKER = Pattern.compile("(?m)^(<<<<<<<|=======|>>>>>>>)\\s");
    private static final Pattern PLACEHOLDER_MARKER = Pattern.compile("(?i)\\b(TBD|FIXME|XXX)\\b");

    /**
     * High-precision "AI edit-artifact" tells: elision placeholders and assistant chatter that a human
     * essentially never commits into source (e.g. a partial LLM paste). Applied to code files only, so
     * prose in Markdown/RST is not flagged. Deterministic, near-zero false positives on real code.
     */
    private static final Pattern AI_EDIT_ARTIFACT = Pattern.compile(
            "(?im)("
            + "\\.\\.\\.\\s*(existing|rest of|remaining)\\b[^\\n]*\\b(code|implementation|unchanged|methods?|functions?|here)"
            + "|\\b(rest|remainder) of (the )?(code|file|implementation|method|function|class)s?\\b[^\\n]*\\b(unchanged|the same|omitted|here)"
            + "|\\bas an ai language model\\b"
            + "|\\bhere('?s| is) the (updated|complete|fixed|full|corrected|revised) (code|implementation|file|version)\\b"
            + "|\\bin a real[- ]world scenario\\b"
            + "|<<<<<<< SEARCH|>>>>>>> REPLACE"
            + "|\\b(YOUR_CODE_HERE|INSERT_YOUR_CODE|INSERT_CODE_HERE)\\b"
            + ")");

    /**
     * Provenance trailers accidentally pasted into source (Apache {@code Generated-by:} / AI co-author lines).
     * Disclosure in commit messages or PR templates is fine; trailers inside product files are not.
     */
    private static final Pattern AI_PROVENANCE_TRAILER = Pattern.compile(
            "(?im)("
            + "^\\s*Generated-by:\\s*\\S+"
            + "|^\\s*Co-Authored-By:\\s*.*(?:noreply@anthropic\\.com|chatgpt|openai|copilot|cursor|gemini|claude)"
            + ")");

    /**
     * Empty / tautology tests that verify nothing (common agent dump). Test paths only; added-lines scoped.
     */
    private static final Pattern PLACEHOLDER_TEST = Pattern.compile(
            "(?im)("
            + "expect\\s*\\(\\s*true\\s*\\)\\s*\\.\\s*toBe\\s*\\(\\s*true\\s*\\)"
            + "|expect\\s*\\(\\s*true\\s*\\)\\s*\\.\\s*toBeTruthy\\s*\\(\\s*\\)"
            + "|assertTrue\\s*\\(\\s*true\\s*\\)"
            + "|assertEquals\\s*\\(\\s*true\\s*,\\s*true\\s*\\)"
            + "|assert\\s+True\\b"
            + "|self\\.assertTrue\\s*\\(\\s*True\\s*\\)"
            + ")");

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs", ".kt", ".kts",
            ".scala", ".c", ".cpp", ".cc", ".h", ".hpp", ".rb", ".sh", ".bash");

    private static boolean isCodeFile(String path) {
        String lower = path.toLowerCase();
        for (String ext : CODE_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    static boolean isTestPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String lower = path.replace('\\', '/').toLowerCase();
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.contains("/__tests__/")
                || name.startsWith("test_")
                || name.endsWith("_test.py")
                || name.endsWith("_test.go")
                || name.endsWith("test.java")
                || name.endsWith("tests.java")
                || name.endsWith(".test.js")
                || name.endsWith(".test.ts")
                || name.endsWith(".test.jsx")
                || name.endsWith(".test.tsx")
                || name.endsWith(".spec.js")
                || name.endsWith(".spec.ts");
    }

    /** True when pattern hits on ADDED files, or on added lines of MODIFIED files (unslop-style). */
    private static boolean matchesOnAddedSurface(
            AddedLineIndex added, ChangedFile f, java.util.regex.Pattern pattern) {
        String content = f.getContent();
        if (f.getChangeType() == ChangedFile.ChangeType.ADDED) {
            return pattern.matcher(content).find();
        }
        return added.markerOnAddedLine(f.getPath(), content, pattern);
    }

    @Override
    public String getId() {
        return "invariant";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        List<String> failures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        AddedLineIndex added = AddedLineIndex.fromRawDiff(context.getDiff().getRawDiff());

        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            String content = f.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (MERGE_CONFLICT_MARKER.matcher(content).find()) {
                String msg = "Merge conflict marker found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.merge-conflict", f.getPath(), 1, msg));
            }
            // Placeholders / AI artifacts: added-lines only when rawDiff is available (unslop-style; avoids
            // FPs on pre-existing FIXME or legacy comments in touched files).
            if (matchesOnAddedSurface(added, f, PLACEHOLDER_MARKER)) {
                String msg = "Placeholder marker (TBD/FIXME/XXX) found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.placeholder", f.getPath(), 1, msg));
            }
            if (isCodeFile(f.getPath()) && matchesOnAddedSurface(added, f, AI_EDIT_ARTIFACT)) {
                String msg = "AI edit-artifact / elision marker found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.ai-edit-artifact", f.getPath(), 1, msg));
            }
            if (matchesOnAddedSurface(added, f, AI_PROVENANCE_TRAILER)) {
                String msg = "AI provenance trailer pasted into " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.ai-provenance", f.getPath(), 1, msg));
            }
            if (isTestPath(f.getPath()) && matchesOnAddedSurface(added, f, PLACEHOLDER_TEST)) {
                String msg = "Placeholder / tautology test found in " + f.getPath();
                failures.add(msg);
                findings.add(Finding.atLine("invariant.placeholder-test", f.getPath(), 1, msg));
            }
        }
        if (failures.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("\n", failures), findings);
    }
}
