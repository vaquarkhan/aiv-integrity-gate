/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.external;

import io.github.vaquarkhan.aiv.util.JsonLite;

import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import io.github.vaquarkhan.aiv.util.FileExtensions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs analyzers the project already trusts (ruff, gitleaks, semgrep, pylint-airflow, and any other
 * tool that writes SARIF) and folds their results into the AIV verdict under the same trust boundary:
 * configuration from the protected branch, findings scoped to changed files, nothing silently skipped.
 *
 * <pre>
 * - id: external
 *   enabled: true
 *   config:
 *     tools:
 *       - id: ruff-airflow
 *         command: ["ruff", "check", "--select", "AIR", "--output-format", "sarif", "--exit-zero", "{files}"]
 *         sarif: stdout                 # or a file path the tool writes
 *         severity: fail                # fail blocks CI; warn is advisory
 *         min_level: warning            # ignore SARIF results below this level (none, note, warning, error)
 *         changed_files_only: true      # drop results outside the diff
 *         file_extensions: [.py]        # which changed files are passed to {files}
 *         timeout_seconds: 120
 *         on_missing: skip              # skip reports the tool as SKIPPED; fail blocks when the tool is absent
 * </pre>
 *
 * Rule ids are {@code external.<tool-id>.<sarif-rule-id>} so suppressions and baselines stay stable.
 */
public final class ExternalToolsGate implements QualityGate {

    public static final String ID = "external";

    /** Outcome of one process execution. */
    record Execution(int exitCode, String stdout, boolean timedOut) {
    }

    /** Seam for tests; production uses {@link ProcessBuilder}. */
    @FunctionalInterface
    interface Runner {
        Execution run(List<String> command, Path cwd, long timeoutSeconds) throws IOException, InterruptedException;
    }

    record Tool(String id, List<String> command, String sarif, boolean blocks, int minLevel,
                boolean changedFilesOnly, Set<String> extensions, long timeoutSeconds, boolean failWhenMissing) {
    }

    private final Runner runner;

    public ExternalToolsGate() {
        this(ExternalToolsGate::runProcess);
    }

    ExternalToolsGate(Runner runner) {
        this.runner = runner;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public GateResult evaluate(AIVContext context) {
        Map<String, Object> cfg = context.getConfig().findGate(ID).map(g -> g.getConfig()).orElse(Map.of());
        List<Tool> tools = parseTools(cfg.get("tools"));
        if (tools.isEmpty()) {
            return new GateResult(ID, true, "No external tools configured", false, List.of());
        }
        Set<String> changed = new HashSet<>();
        for (ChangedFile f : context.getDiff().getChangedFiles()) {
            changed.add(norm(f.getPath()));
        }

        List<Finding> blocking = new ArrayList<>();
        List<Finding> advisory = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Tool tool : tools) {
            List<String> files = new ArrayList<>();
            for (String p : changed) {
                if (tool.extensions().isEmpty() || FileExtensions.matches(p, tool.extensions())) {
                    files.add(p);
                }
            }
            if (files.isEmpty() && tool.command().contains("{files}")) {
                notes.add(tool.id() + ": no changed files match, not run");
                continue;
            }
            List<String> command = expand(tool.command(), files);
            Execution exec;
            try {
                exec = runner.run(command, context.getWorkspace(), tool.timeoutSeconds());
            } catch (IOException e) {
                String msg = tool.id() + ": tool not available (" + e.getMessage() + ")";
                if (tool.failWhenMissing()) {
                    blocking.add(Finding.atLine("external." + tool.id() + ".missing", "(diff)", 1, msg));
                } else {
                    notes.add("SKIPPED " + msg);
                }
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notes.add("SKIPPED " + tool.id() + ": interrupted");
                continue;
            }
            if (exec.timedOut()) {
                String msg = tool.id() + ": timed out after " + tool.timeoutSeconds() + "s";
                if (tool.failWhenMissing()) {
                    blocking.add(Finding.atLine("external." + tool.id() + ".timeout", "(diff)", 1, msg));
                } else {
                    notes.add("SKIPPED " + msg);
                }
                continue;
            }
            String sarif = readSarif(tool, exec, context.getWorkspace());
            if (sarif == null) {
                blocking.add(Finding.atLine("external." + tool.id() + ".no-output", "(diff)", 1,
                        tool.id() + ": produced no SARIF output (exit " + exec.exitCode() + ")"));
                continue;
            }
            List<SarifReader.Result> results;
            try {
                results = SarifReader.read(sarif);
            } catch (IllegalArgumentException e) {
                blocking.add(Finding.atLine("external." + tool.id() + ".bad-output", "(diff)", 1,
                        tool.id() + ": output is not valid SARIF (" + e.getMessage() + ")"));
                continue;
            }
            int kept = 0;
            for (SarifReader.Result r : results) {
                if (SarifReader.rank(r.level()) < tool.minLevel()) {
                    continue;
                }
                String path = r.uri() == null ? "(diff)" : relativize(r.uri(), context.getWorkspace());
                if (tool.changedFilesOnly() && r.uri() != null && !changed.contains(path)) {
                    continue;
                }
                kept++;
                Finding f = Finding.atLine("external." + tool.id() + "." + r.ruleId(), path, r.startLine(),
                        "[" + tool.id() + " " + r.level() + "] " + r.message());
                (tool.blocks() ? blocking : advisory).add(f);
            }
            notes.add(tool.id() + ": " + kept + " finding" + (kept == 1 ? "" : "s"));
        }

        String summary = String.join("; ", notes);
        if (!blocking.isEmpty()) {
            List<Finding> all = new ArrayList<>(blocking);
            all.addAll(advisory);
            return GateResult.fail(ID, summary + "\n" + join(all), all);
        }
        if (!advisory.isEmpty()) {
            return GateResult.advisory(ID, summary + "\n" + join(advisory), advisory);
        }
        return new GateResult(ID, true, summary, false, List.of());
    }

    static List<Tool> parseTools(Object raw) {
        List<Tool> out = new ArrayList<>();
        for (Object o : JsonLite.list(raw)) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String id = str(m.get("id"));
            List<String> command = strings(m.get("command"));
            if (id == null || id.isBlank() || command.isEmpty()) {
                throw new IllegalArgumentException("external.tools entries need 'id' and a non-empty 'command' list");
            }
            String sarif = m.get("sarif") == null ? "stdout" : str(m.get("sarif"));
            boolean blocks = !"warn".equalsIgnoreCase(str(m.get("severity")));
            String minLevel = m.get("min_level") == null ? "warning" : str(m.get("min_level")).toLowerCase(Locale.ROOT);
            boolean changedOnly = !Boolean.FALSE.equals(m.get("changed_files_only"));
            Set<String> exts = (m.containsKey("file_extensions") || m.containsKey("languages"))
                    ? FileExtensions.fromConfig(toStringMap(m)) : Set.of();
            long timeout = m.get("timeout_seconds") instanceof Number n ? n.longValue() : 120L;
            boolean failWhenMissing = "fail".equalsIgnoreCase(str(m.get("on_missing")));
            out.add(new Tool(id.trim().toLowerCase(Locale.ROOT), command, sarif, blocks, SarifReader.rank(minLevel),
                    changedOnly, exts, timeout, failWhenMissing));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    static List<String> expand(List<String> command, List<String> files) {
        List<String> out = new ArrayList<>();
        for (String part : command) {
            if ("{files}".equals(part)) {
                out.addAll(files);
            } else {
                out.add(part);
            }
        }
        return out;
    }

    private static String readSarif(Tool tool, Execution exec, Path workspace) {
        if ("stdout".equalsIgnoreCase(tool.sarif())) {
            return exec.stdout() == null || exec.stdout().isBlank() ? null : exec.stdout();
        }
        Path p = Path.of(tool.sarif());
        if (!p.isAbsolute() && workspace != null) {
            p = workspace.resolve(p);
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static String relativize(String uri, Path workspace) {
        return relativize(uri, workspace == null ? null : workspace.toAbsolutePath().toString());
    }

    static String relativize(String uri, String workspacePath) {
        String u = norm(uri);
        if (workspacePath != null) {
            String ws = norm(workspacePath);
            if (!ws.endsWith("/")) {
                ws = ws + "/";
            }
            if (u.startsWith(ws)) {
                return u.substring(ws.length());
            }
            if (u.startsWith("/") && u.length() > 2 && u.charAt(2) == ':' && u.substring(1).startsWith(ws)) {
                return u.substring(1 + ws.length());
            }
        }
        return u;
    }

    static Execution runProcess(List<String> command, Path cwd, long timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        // Drain stdout on its own thread so a tool that never exits cannot block the timeout.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> drain(p.getInputStream(), buf), "aiv-external-stdout");
        reader.start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            // Kill the tree: a wrapper script (ruff.cmd, npx, a shell) can leave the real tool running.
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(5));
            return new Execution(-1, "", true);
        }
        reader.join();
        return new Execution(p.exitValue(), buf.toString(StandardCharsets.UTF_8), false);
    }

    /** Copies the stream to the buffer; returns false when the stream failed (process killed). */
    static boolean drain(InputStream in, ByteArrayOutputStream out) {
        try {
            in.transferTo(out);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String join(List<Finding> fs) {
        List<String> msgs = new ArrayList<>();
        for (Finding f : fs) {
            msgs.add(f.getFilePath() + ":" + f.getStartLine() + " " + f.getMessage());
        }
        return String.join("\n", msgs);
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : JsonLite.list(v)) {
            if (o != null && !o.toString().isBlank()) {
                out.add(o.toString());
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String norm(String p) {
        return p == null ? "" : p.replace('\\', '/');
    }
}
