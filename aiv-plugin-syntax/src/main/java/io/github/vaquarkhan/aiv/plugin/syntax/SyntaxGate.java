/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.syntax;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.List;

/**
 * Parse-validity pre-gate. Flags changed files that do not parse - a guaranteed downstream CI
 * failure caught in milliseconds. Runs in-process for Java (JavaParser) and YAML/JSON (SnakeYAML),
 * and delegates Python to an interpreter via {@link PythonSyntaxValidator} (skipped when absent).
 * Designed as a cheap, near-zero-false-positive check to run before an expensive test matrix.
 *
 * @author Vaquar Khan
 */
public final class SyntaxGate implements QualityGate {

    private static final JavaParser JAVA_PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    private final SourceValidator pythonValidator;

    public SyntaxGate() {
        this(new PythonSyntaxValidator(new ProcessCommandExecutor()));
    }

    SyntaxGate(SourceValidator pythonValidator) {
        this.pythonValidator = pythonValidator;
    }

    @Override
    public String getId() {
        return "syntax";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        List<String> failures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            String content = file.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            ValidationResult vr = validateByType(file.getPath().toLowerCase(), content);
            if (vr == null || vr.valid()) {
                continue;
            }
            int line = vr.line() != null ? vr.line() : 1;
            String msg = String.format("Does not parse: %s (%s)", file.getPath(), vr.detail());
            failures.add(msg);
            findings.add(Finding.atLine("syntax.parse", file.getPath(), line, msg));
        }
        if (failures.isEmpty()) {
            return GateResult.pass(getId());
        }
        return GateResult.fail(getId(), String.join("\n", failures), findings);
    }

    private ValidationResult validateByType(String lowerPath, String content) {
        if (lowerPath.endsWith(".java")) {
            // A ".java" file whose first non-blank line starts with '#' is not Java (e.g. a Dockerfile
            // named Dockerfile.java). Java has no '#' line syntax, so skip rather than false-flag it.
            if (startsWithHash(content)) {
                return ValidationResult.ok();
            }
            return validateJava(content);
        }
        if (lowerPath.endsWith(".py")) {
            return pythonValidator.validate(content);
        }
        if (lowerPath.endsWith(".yaml") || lowerPath.endsWith(".yml")) {
            return validateYamlLike(content);
        }
        if (lowerPath.endsWith(".json")) {
            // TypeScript project config (tsconfig*.json) is JSONC by spec (comments, trailing commas)
            // and is intentionally not strict JSON/YAML: skip, do not flag.
            if (isTsConfig(lowerPath)) {
                return ValidationResult.ok();
            }
            return validateYamlLike(content);
        }
        return null;
    }

    static boolean startsWithHash(String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                continue;
            }
            return c == '#';
        }
        return false;
    }

    static boolean isTsConfig(String lowerPath) {
        int slash = Math.max(lowerPath.lastIndexOf('/'), lowerPath.lastIndexOf('\\'));
        String name = slash >= 0 ? lowerPath.substring(slash + 1) : lowerPath;
        return name.startsWith("tsconfig") && name.endsWith(".json");
    }

    static ValidationResult validateJava(String content) {
        ParseResult<CompilationUnit> result = JAVA_PARSER.parse(content);
        if (result.isSuccessful()) {
            return ValidationResult.ok();
        }
        String detail = "unparseable";
        if (!result.getProblems().isEmpty()) {
            detail = result.getProblems().get(0).getMessage();
        }
        return ValidationResult.fail(1, detail);
    }

    static ValidationResult validateYamlLike(String content) {
        // Templated YAML/JSON (Helm {{ }}, Jinja {% %}) is intentionally not valid YAML: skip, do not flag.
        if (content.contains("{{") || content.contains("{%")) {
            return ValidationResult.ok();
        }
        try {
            // loadAll (not load) so multi-document streams (k8s / kustomize manifests with '---'
            // separators) are accepted; iterate to force lazy parsing of every document.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            for (Object ignored : yaml.loadAll(content)) {
                // no-op: iteration drives the parse of each document
            }
            return ValidationResult.ok();
        } catch (YAMLException parseError) {
            Integer line = 1;
            if (parseError instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
                line = marked.getProblemMark().getLine() + 1;
            }
            return ValidationResult.fail(line, parseError.getMessage());
        }
    }
}
