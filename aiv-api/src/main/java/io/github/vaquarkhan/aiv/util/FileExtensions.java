/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.util;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves file extensions from gate config. Supports multi-language validation.
 *
 * @author Vaquar Khan
 */
public final class FileExtensions {

    private static final Set<String> DEFAULT_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".go", ".rs", ".scala",
            ".js", ".ts", ".jsx", ".tsx", ".c", ".cpp", ".cc", ".h", ".hpp",
            ".rb", ".sh", ".bash"
    );

    private FileExtensions() {
    }

    /**
     * Returns the set of file extensions to validate. Reads from config key
     * {@code file_extensions} (list of strings) or {@code languages} (mapped to extensions).
     * If absent, returns default multi-language extensions.
     */
    public static Set<String> fromConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return DEFAULT_EXTENSIONS;
        }
        Object extObj = config.get("file_extensions");
        if (extObj instanceof List<?> list) {
            Set<String> result = list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(s -> s.startsWith(".") ? s : "." + s)
                    .collect(Collectors.toSet());
            return result.isEmpty() ? DEFAULT_EXTENSIONS : result;
        }
        Object langObj = config.get("languages");
        if (langObj instanceof List<?> list) {
            Set<String> result = list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .flatMap(FileExtensions::extensionsForLanguage)
                    .collect(Collectors.toSet());
            return result.isEmpty() ? DEFAULT_EXTENSIONS : result;
        }
        return DEFAULT_EXTENSIONS;
    }

    private static Stream<String> extensionsForLanguage(String lang) {
        return switch (lang.toLowerCase()) {
            case "java" -> Stream.of(".java");
            case "kotlin", "kt" -> Stream.of(".kt", ".kts");
            case "python", "py" -> Stream.of(".py");
            case "go", "golang" -> Stream.of(".go");
            case "rust", "rs" -> Stream.of(".rs");
            case "scala", "sc" -> Stream.of(".scala", ".sc");
            case "javascript", "js" -> Stream.of(".js", ".jsx", ".mjs");
            case "typescript", "ts" -> Stream.of(".ts", ".tsx", ".mts");
            case "c" -> Stream.of(".c", ".h");
            case "cpp", "c++" -> Stream.of(".cpp", ".cc", ".cxx", ".hpp", ".hxx");
            case "ruby", "rb" -> Stream.of(".rb");
            case "shell", "sh", "bash" -> Stream.of(".sh", ".bash");
            default -> Stream.of("." + lang);
        };
    }

    public static boolean matches(String path, Set<String> extensions) {
        if (path == null || path.isEmpty() || extensions == null || extensions.isEmpty()) {
            return false;
        }
        String lower = path.toLowerCase();
        for (String ext : extensions) {
            if (lower.endsWith(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
