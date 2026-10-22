/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.design;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;

/**
 * Strips Java comments and string literals so design rules do not match mentions in prose or literals.
 */
final class JavaDesignSurface {

    private static final JavaParser JAVA_PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    static String lowerForRuleMatching(String path, String content) {
        if (content == null) {
            return "";
        }
        if (path == null || !path.toLowerCase().endsWith(".java")) {
            return content.toLowerCase();
        }
        ParseResult<CompilationUnit> parsed = JAVA_PARSER.parse(content);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return content.toLowerCase();
        }
        CompilationUnit cu = parsed.getResult().get();
        cu.findAll(Comment.class).forEach(Comment::remove);
        cu.findAll(StringLiteralExpr.class).forEach(s -> s.setString(""));
        cu.findAll(TextBlockLiteralExpr.class).forEach(tb -> tb.setValue(""));
        return cu.toString().toLowerCase();
    }

    private JavaDesignSurface() {
    }
}
