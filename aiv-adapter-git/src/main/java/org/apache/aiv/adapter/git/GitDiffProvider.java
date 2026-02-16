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

package org.apache.aiv.adapter.git;

import org.apache.aiv.model.ChangedFile;
import org.apache.aiv.model.Diff;
import org.apache.aiv.port.DiffProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Git-based diff provider. Runs git diff and parses output.
 *
 * @author Vaquar Khan
 */
public final class GitDiffProvider implements DiffProvider {

    @Override
    public Diff getDiff(Path workspace, String baseRef, String headRef) {
        String rawDiff = runGitDiff(workspace, baseRef, headRef);
        List<ChangedFile> files = parseChangedFiles(workspace, baseRef, headRef, rawDiff);
        int[] loc = parseNumStat(workspace, baseRef, headRef);
        String author = parseAuthor(workspace, baseRef, headRef);
        boolean skip = parseSkipRequested(workspace, baseRef, headRef);
        return new Diff(baseRef, headRef, files, rawDiff, loc[0], loc[1], author, skip);
    }

    private int[] parseNumStat(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--numstat", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int added = 0, deleted = 0;
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        added += parseNum(parts[0]);
                        deleted += parseNum(parts[1]);
                    }
                }
            }
            return new int[]{added, deleted};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private int parseNum(String s) {
        if (s == null || s.equals("-")) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String parseAuthor(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "log", "-1", "--format=%ae", baseRef + ".." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                return (line != null && !line.isBlank()) ? line.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private boolean parseSkipRequested(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "log", "--pretty=%B", baseRef + ".." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("/aiv skip") || line.contains("aiv skip")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private String runGitDiff(Path workspace, String baseRef, String headRef) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return "";
        }
    }

    private List<ChangedFile> parseChangedFiles(Path workspace, String baseRef, String headRef, String rawDiff) {
        List<ChangedFile> result = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-status", baseRef + "..." + headRef);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length < 2) continue;
                    String status = parts[0];
                    String path = parts[1];
                    ChangedFile.ChangeType type = "A".equals(status) ? ChangedFile.ChangeType.ADDED : ChangedFile.ChangeType.MODIFIED;
                    String content = readFileContent(workspace, path, headRef);
                    result.add(new ChangedFile(path, type, content));
                }
            }
        } catch (Exception e) {
            // fallback: no files
        }
        return result;
    }

    private String readFileContent(Path workspace, String relativePath, String ref) {
        Path fullPath = workspace.resolve(relativePath);
        try {
            if (Files.exists(fullPath)) {
                return Files.readString(fullPath);
            }
            String gitPath = relativePath.replace("\\", "/");
            ProcessBuilder pb = new ProcessBuilder("git", "show", ref + ":" + gitPath);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return "";
        }
    }
}
