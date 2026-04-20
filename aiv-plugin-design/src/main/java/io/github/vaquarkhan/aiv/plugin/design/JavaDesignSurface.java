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
