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

package org.apache.aiv.plugin.density;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.apache.aiv.model.AIVConfig;
import org.apache.aiv.model.AIVContext;
import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.GateResult;
import org.apache.aiv.port.QualityGate;
import org.apache.aiv.util.FileExtensions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logic density gate. Flags code with low ratio of control flow / transforms vs scaffolding.
 * LDR check runs for Java only; entropy check runs for all configured languages.
 * Skips when net LOC is negative (refactoring) or author is trusted.
 *
 * @author Vaquar Khan
 */
public final class DensityGate implements QualityGate {

    private static final double DEFAULT_LDR_THRESHOLD = 0.25;
    private static final double DEFAULT_ENTROPY_THRESHOLD = 3.8;
    private static final int DEFAULT_REFACTOR_THRESHOLD = -50;

    @Override
    public String getId() {
        return "density";
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        if (isTrustedAuthor(context)) {
            return GateResult.pass(getId());
        }
        if (isRefactoring(context)) {
            return GateResult.pass(getId());
        }
        double ldrThreshold = getThreshold(context, "ldr_threshold", DEFAULT_LDR_THRESHOLD);
        double entropyThreshold = getThreshold(context, "entropy_threshold", DEFAULT_ENTROPY_THRESHOLD);
        Set<String> extensions = FileExtensions.fromConfig(getGateConfig(context));

        for (ChangedFile file : context.getDiff().getChangedFiles()) {
            if (!FileExtensions.matches(file.getPath(), extensions)) {
                continue;
            }
            String content = file.getContent();
            if (content.isEmpty()) {
                continue;
            }

            double entropy = calculateEntropy(content);
            if (entropy < entropyThreshold) {
                return GateResult.fail(getId(),
                        String.format("Low entropy (%.2f) in %s - possible boilerplate", entropy, file.getPath()));
            }

            if (file.getPath().toLowerCase().endsWith(".java")) {
                double ldr = calculateLdr(content);
                if (ldr < ldrThreshold) {
                    return GateResult.fail(getId(),
                            String.format("Low logic density (%.2f) in %s - threshold %.2f", ldr, file.getPath(), ldrThreshold));
                }
            }
        }
        return GateResult.pass(getId());
    }

    private boolean isTrustedAuthor(AIVContext context) {
        String author = context.getDiff().getAuthorEmail();
        if (author == null || author.isBlank()) return false;
        List<String> trusted = getTrustedAuthors(context);
        return trusted.stream().anyMatch(t -> t.equalsIgnoreCase(author.trim()));
    }

    @SuppressWarnings("unchecked")
    private List<String> getTrustedAuthors(AIVContext context) {
        Object v = getGateConfig(context).get("trusted_authors");
        if (v instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private boolean isRefactoring(AIVContext context) {
        int threshold = getIntThreshold(context, "refactor_net_loc_threshold", DEFAULT_REFACTOR_THRESHOLD);
        return context.getDiff().getNetLoc() <= threshold;
    }

    private int getIntThreshold(AIVContext context, String key, int defaultValue) {
        Object v = getGateConfig(context).get(key);
        if (v instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private Map<String, Object> getGateConfig(AIVContext context) {
        return context.getConfig().getGates().stream()
                .filter(g -> getId().equals(g.getId()))
                .findFirst()
                .map(AIVConfig.GateConfig::getConfig)
                .orElse(Map.of());
    }

    private double getThreshold(AIVContext context, String key, double defaultValue) {
        return context.getConfig().getGates().stream()
                .filter(g -> getId().equals(g.getId()))
                .findFirst()
                .map(g -> {
                    Object v = g.getConfig().get(key);
                    if (v instanceof Number) {
                        return ((Number) v).doubleValue();
                    }
                    return defaultValue;
                })
                .orElse(defaultValue);
    }

    static double calculateEntropy(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Map<Character, Integer> counts = new HashMap<>();
        for (char c : text.toCharArray()) {
            counts.merge(c, 1, Integer::sum);
        }
        double entropy = 0;
        int len = text.length();
        for (Integer count : counts.values()) {
            double p = (double) count / len;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    static double calculateLdr(String source) {
        ParseResult<CompilationUnit> parseResult = new JavaParser().parse(source);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return 0;
        }
        CompilationUnit cu = parseResult.getResult().get();
        DensityVisitor visitor = new DensityVisitor();
        visitor.visit(cu, null);
        int total = visitor.logicNodes + visitor.structureNodes;
        return total == 0 ? 0 : (double) visitor.logicNodes / total;
    }

    private static class DensityVisitor extends VoidVisitorAdapter<Void> {
        int logicNodes = 0;
        int structureNodes = 0;

        @Override
        public void visit(com.github.javaparser.ast.stmt.IfStmt n, Void arg) {
            logicNodes += 5;
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.ForStmt n, Void arg) {
            logicNodes += 5;
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.WhileStmt n, Void arg) {
            logicNodes += 5;
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.SwitchStmt n, Void arg) {
            logicNodes += 5;
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.MethodCallExpr n, Void arg) {
            logicNodes += 2;
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.BinaryExpr n, Void arg) {
            logicNodes += 2;
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration n, Void arg) {
            structureNodes += 1;
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.body.MethodDeclaration n, Void arg) {
            structureNodes += 1;
            super.visit(n, arg);
        }
    }
}
