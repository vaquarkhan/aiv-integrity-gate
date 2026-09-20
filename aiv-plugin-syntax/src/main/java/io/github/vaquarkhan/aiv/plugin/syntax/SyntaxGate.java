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
 * Parse-validity pre-gate. Java / YAML / JSON in-process; Python / JS / TS / Go via external
 * toolchains when present (skipped when absent — precision-first).
 */
public final class SyntaxGate implements QualityGate {

    private static final JavaParser JAVA_PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    private final SourceValidator pythonValidator;
    private final SourceValidator jsValidator;
    private final SourceValidator tsValidator;
    private final SourceValidator goValidator;

    public SyntaxGate() {
        this(new PythonSyntaxValidator(new ProcessCommandExecutor()),
                new NodeSyntaxValidator(new ProcessCommandExecutor(), false, false),
                new NodeSyntaxValidator(new ProcessCommandExecutor(), true, false),
                new GoSyntaxValidator(new ProcessCommandExecutor()));
    }

    /** Test hook: python only; other languages treated as OK skip. */
    SyntaxGate(SourceValidator pythonValidator) {
        this(pythonValidator, src -> ValidationResult.ok(), src -> ValidationResult.ok(), src -> ValidationResult.ok());
    }

    SyntaxGate(SourceValidator pythonValidator, SourceValidator jsValidator,
               SourceValidator tsValidator, SourceValidator goValidator) {
        this.pythonValidator = pythonValidator;
        this.jsValidator = jsValidator;
        this.tsValidator = tsValidator;
        this.goValidator = goValidator;
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
            if (startsWithHash(content)) {
                return ValidationResult.ok();
            }
            return validateJava(content);
        }
        if (lowerPath.endsWith(".py")) {
            return pythonValidator.validate(content);
        }
        if (lowerPath.endsWith(".jsx") || lowerPath.endsWith(".tsx")) {
            // JSX needs a transform; Node --check alone is high-FP — skip (precision-first).
            return ValidationResult.ok();
        }
        if (lowerPath.endsWith(".ts") || lowerPath.endsWith(".mts")) {
            return tsValidator.validate(content);
        }
        if (lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs") || lowerPath.endsWith(".cjs")) {
            return jsValidator.validate(content);
        }
        if (lowerPath.endsWith(".go")) {
            return goValidator.validate(content);
        }
        if (lowerPath.endsWith(".yaml") || lowerPath.endsWith(".yml")) {
            return validateYamlLike(content);
        }
        if (lowerPath.endsWith(".json")) {
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
        if (content.contains("{{") || content.contains("{%")) {
            return ValidationResult.ok();
        }
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            for (Object ignored : yaml.loadAll(content)) {
                // drive parse
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
