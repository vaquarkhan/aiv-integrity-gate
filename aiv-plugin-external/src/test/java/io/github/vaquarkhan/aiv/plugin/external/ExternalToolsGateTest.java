/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.external;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalToolsGateTest {

    private static final String SARIF = """
            {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"ruff"}},"results":[
              {"ruleId":"AIR301","level":"error","message":{"text":"removed import"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"dags/a.py"},"region":{"startLine":3}}}]},
              {"ruleId":"AIR001","level":"warning","message":{"text":"id mismatch"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"file://dags/other.py"},"region":{"startLine":9}}}]},
              {"ruleId":"N1","level":"note","message":{"text":"nit"},
               "locations":[{"physicalLocation":{"artifactLocation":{"uri":"./dags/a.py"},"region":{"startLine":0}}}]},
              {"message":{"text":"no rule, no location"}}
            ]}]}
            """;

    private static AIVContext ctx(Path ws, Map<String, Object> cfg, ChangedFile... files) {
        var config = new AIVConfig(List.of(new AIVConfig.GateConfig(ExternalToolsGate.ID, true, cfg)), Map.of());
        return new AIVContext(ws, new Diff("main", "HEAD", List.of(files), ""), config);
    }

    private static Map<String, Object> tools(Map<String, Object>... entries) {
        return Map.of("tools", List.of(entries));
    }

    private static Map<String, Object> tool(String id, String severity, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("command", List.of("ruff", "check", "--output-format", "sarif", "{files}"));
        if (severity != null) {
            m.put("severity", severity);
        }
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void registeredAndNoToolsIsPass(@TempDir Path ws) {
        assertTrue(ServiceLoader.load(QualityGate.class).stream().anyMatch(p -> p.type() == ExternalToolsGate.class));
        assertEquals("external", new ExternalToolsGate().getId());
        GateResult r = new ExternalToolsGate().evaluate(ctx(ws, Map.of()));
        assertTrue(r.isPassed());
        assertTrue(r.getMessage().contains("No external tools"));
    }

    @Test
    void sarifResultsBecomeFindingsScopedToChangedFiles(@TempDir Path ws) {
        List<List<String>> seen = new ArrayList<>();
        ExternalToolsGate gate = new ExternalToolsGate((cmd, cwd, t) -> {
            seen.add(cmd);
            return new ExternalToolsGate.Execution(1, SARIF, false);
        });
        GateResult r = gate.evaluate(ctx(ws, tools(tool("ruff", "fail", "file_extensions", List.of(".py"))),
                new ChangedFile("dags/a.py", ChangedFile.ChangeType.MODIFIED, "x"),
                new ChangedFile("README.md", ChangedFile.ChangeType.MODIFIED, "x")));
        assertFalse(r.isPassed());
        assertTrue(r.blocksCi());
        // AIR301 on a changed file kept; AIR001 on other.py dropped (not in diff); note-level dropped; no-location kept as (diff)
        assertEquals(2, r.getFindings().size());
        assertEquals("external.ruff.AIR301", r.getFindings().get(0).getRuleId());
        assertEquals("dags/a.py", r.getFindings().get(0).getFilePath());
        assertEquals(3, r.getFindings().get(0).getStartLine());
        assertEquals("external.ruff.unknown", r.getFindings().get(1).getRuleId());
        assertEquals("(diff)", r.getFindings().get(1).getFilePath());
        assertEquals(List.of("ruff", "check", "--output-format", "sarif", "dags/a.py"), seen.get(0));
        assertTrue(r.getMessage().startsWith("ruff: 2 findings"));
    }

    @Test
    void warnSeverityAndMinLevelAndChangedFilesOnlyOff(@TempDir Path ws) {
        ExternalToolsGate gate = new ExternalToolsGate((cmd, cwd, t) -> new ExternalToolsGate.Execution(0, SARIF, false));
        GateResult r = gate.evaluate(ctx(ws, tools(tool("ruff", "warn", "min_level", "note", "changed_files_only", false)),
                new ChangedFile("dags/a.py", ChangedFile.ChangeType.MODIFIED, "x")));
        assertFalse(r.isPassed());
        assertFalse(r.blocksCi());
        assertEquals(4, r.getFindings().size());
        assertTrue(r.getFindings().stream().anyMatch(f -> "dags/other.py".equals(f.getFilePath())));
    }

    @Test
    void missingToolSkipsOrFailsPerConfig(@TempDir Path ws) {
        ExternalToolsGate gate = new ExternalToolsGate((cmd, cwd, t) -> {
            throw new IOException("not found");
        });
        var file = new ChangedFile("a.py", ChangedFile.ChangeType.ADDED, "x");
        GateResult skip = gate.evaluate(ctx(ws, tools(tool("semgrep", "fail")), file));
        assertTrue(skip.isPassed());
        assertTrue(skip.getMessage().contains("SKIPPED semgrep: tool not available"));
        GateResult fail = gate.evaluate(ctx(ws, tools(tool("semgrep", "fail", "on_missing", "fail")), file));
        assertFalse(fail.isPassed());
        assertEquals("external.semgrep.missing", fail.getFindings().get(0).getRuleId());
    }

    @Test
    void timeoutInterruptedNoOutputAndBadOutput(@TempDir Path ws) {
        var file = new ChangedFile("a.py", ChangedFile.ChangeType.ADDED, "x");
        GateResult timeoutSkip = new ExternalToolsGate((c, w, t) -> new ExternalToolsGate.Execution(-1, "", true))
                .evaluate(ctx(ws, tools(tool("slow", "fail", "timeout_seconds", 1)), file));
        assertTrue(timeoutSkip.isPassed());
        assertTrue(timeoutSkip.getMessage().contains("timed out after 1s"));
        GateResult timeoutFail = new ExternalToolsGate((c, w, t) -> new ExternalToolsGate.Execution(-1, "", true))
                .evaluate(ctx(ws, tools(tool("slow", "fail", "on_missing", "fail")), file));
        assertEquals("external.slow.timeout", timeoutFail.getFindings().get(0).getRuleId());

        GateResult interrupted = new ExternalToolsGate((c, w, t) -> {
            throw new InterruptedException();
        }).evaluate(ctx(ws, tools(tool("x", "fail")), file));
        assertTrue(Thread.interrupted(), "interrupt flag restored");
        assertTrue(interrupted.getMessage().contains("SKIPPED x: interrupted"));

        GateResult noOutput = new ExternalToolsGate((c, w, t) -> new ExternalToolsGate.Execution(2, "  ", false))
                .evaluate(ctx(ws, tools(tool("x", "fail")), file));
        assertEquals("external.x.no-output", noOutput.getFindings().get(0).getRuleId());
        assertTrue(noOutput.getMessage().contains("exit 2"));

        GateResult bad = new ExternalToolsGate((c, w, t) -> new ExternalToolsGate.Execution(0, "{not json", false))
                .evaluate(ctx(ws, tools(tool("x", "fail")), file));
        assertEquals("external.x.bad-output", bad.getFindings().get(0).getRuleId());
    }

    @Test
    void sarifFromFileAndNoMatchingFiles(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("out.sarif"), SARIF, StandardCharsets.UTF_8);
        ExternalToolsGate gate = new ExternalToolsGate((c, w, t) -> new ExternalToolsGate.Execution(0, "", false));
        GateResult r = gate.evaluate(ctx(ws, tools(tool("gl", "fail", "sarif", "out.sarif")),
                new ChangedFile("dags/a.py", ChangedFile.ChangeType.ADDED, "x")));
        assertFalse(r.isPassed());
        GateResult missingFile = gate.evaluate(ctx(ws, tools(tool("gl", "fail", "sarif", ws.resolve("none.sarif").toString())),
                new ChangedFile("dags/a.py", ChangedFile.ChangeType.ADDED, "x")));
        assertEquals("external.gl.no-output", missingFile.getFindings().get(0).getRuleId());
        // no changed file matches the tool's extensions and the command uses {files}: tool not run
        GateResult notRun = gate.evaluate(ctx(ws, tools(tool("gl", "fail", "file_extensions", List.of(".java"))),
                new ChangedFile("dags/a.py", ChangedFile.ChangeType.ADDED, "x")));
        assertTrue(notRun.isPassed());
        assertTrue(notRun.getMessage().contains("no changed files match"));
        // tool without {files} runs even when nothing matches
        Map<String, Object> whole = tool("gl2", "warn", "sarif", "out.sarif", "languages", List.of("java"));
        whole.put("command", List.of("gitleaks", "detect"));
        assertFalse(gate.evaluate(ctx(ws, tools(whole), new ChangedFile("dags/a.py", ChangedFile.ChangeType.ADDED, "x"))).isPassed());
    }

    @Test
    void parseToolsValidatesAndDefaults() {
        assertThrows(IllegalArgumentException.class, () -> ExternalToolsGate.parseTools(List.of(Map.of("id", "x"))));
        assertThrows(IllegalArgumentException.class, () -> ExternalToolsGate.parseTools(List.of(Map.of("command", List.of("x")))));
        List<ExternalToolsGate.Tool> tools = ExternalToolsGate.parseTools(Arrays.asList("not-a-map",
                Map.of("id", " Ruff ", "command", List.of("ruff"))));
        assertEquals(1, tools.size());
        ExternalToolsGate.Tool t = tools.get(0);
        assertEquals("ruff", t.id());
        assertEquals("stdout", t.sarif());
        assertTrue(t.blocks());
        assertEquals(2, t.minLevel());
        assertTrue(t.changedFilesOnly());
        assertTrue(t.extensions().isEmpty());
        assertEquals(120L, t.timeoutSeconds());
        assertFalse(t.failWhenMissing());
        assertTrue(ExternalToolsGate.parseTools(null).isEmpty());
        assertEquals(List.of("a", "f1", "f2", "b"), ExternalToolsGate.expand(List.of("a", "{files}", "b"), List.of("f1", "f2")));
        // command arguments keep their exact text; only null and blank entries are dropped
        assertEquals(List.of(" x"), ExternalToolsGate.strings(Arrays.asList(" x", null, "")));
        assertEquals("", ExternalToolsGate.norm(null));
    }

    @Test
    void relativizeHandlesAbsoluteAndWindowsUris(@TempDir Path ws) {
        String abs = ws.toAbsolutePath().toString().replace('\\', '/') + "/dags/a.py";
        assertEquals("dags/a.py", ExternalToolsGate.relativize(abs, ws));
        // SARIF tools on Windows often emit file:///C:/... which normalizes to a leading slash before the drive
        assertEquals("dags/a.py", ExternalToolsGate.relativize("/C:/work/repo/dags/a.py", "C:\\work\\repo"));
        assertEquals("dags/a.py", ExternalToolsGate.relativize("C:/work/repo/dags/a.py", "C:/work/repo/"));
        assertEquals("/C:/other/a.py", ExternalToolsGate.relativize("/C:/other/a.py", "C:/work/repo"));
        assertEquals("a.py", ExternalToolsGate.relativize("a.py", (String) null));
        assertEquals("elsewhere/a.py", ExternalToolsGate.relativize("elsewhere/a.py", ws));
        assertEquals("a.py", ExternalToolsGate.relativize("a.py", (Path) null));
        assertEquals("/x/y.py", ExternalToolsGate.relativize("/x/y.py", ws));
    }

    @Test
    void realProcessRunnerExecutesTimesOutAndReportsMissing(@TempDir Path ws) throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> echo = win ? List.of("cmd", "/c", "echo hello") : List.of("sh", "-c", "echo hello");
        ExternalToolsGate.Execution ok = ExternalToolsGate.runProcess(echo, ws, 30);
        assertEquals(0, ok.exitCode());
        assertTrue(ok.stdout().trim().equals("hello"));
        assertFalse(ok.timedOut());
        List<String> sleep = win ? List.of("cmd", "/c", "ping -n 10 127.0.0.1 > nul") : List.of("sh", "-c", "sleep 10");
        ExternalToolsGate.Execution slow = ExternalToolsGate.runProcess(sleep, null, 1);
        assertTrue(slow.timedOut());
        assertThrows(IOException.class, () -> ExternalToolsGate.runProcess(List.of("aiv-definitely-missing-binary-xyz"), ws, 5));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        assertTrue(ExternalToolsGate.drain(new java.io.ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), buf));
        assertEquals("abc", buf.toString(StandardCharsets.UTF_8));
        assertFalse(ExternalToolsGate.drain(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }
        }, buf));
    }

    @Test
    void sarifReaderAndJsonLite() {
        List<SarifReader.Result> rs = SarifReader.read(SARIF);
        assertEquals(4, rs.size());
        assertEquals("dags/other.py", rs.get(1).uri());
        assertEquals("dags/a.py", rs.get(2).uri());
        assertEquals(1, rs.get(2).startLine());
        assertNull(rs.get(3).uri());
        assertEquals("unknown", rs.get(3).ruleId());
        assertEquals("warning", rs.get(3).level());
        assertTrue(SarifReader.read("{}").isEmpty());
        assertTrue(SarifReader.read("{\"runs\":[{\"results\":[{\"ruleId\":\"r\",\"locations\":[{}]}]}]}").get(0).message().isEmpty());
        assertNull(SarifReader.normalizeUri("  "));
        assertEquals("a/b.py", SarifReader.normalizeUri("a\\b.py"));
        assertEquals(0, SarifReader.rank("none"));
        assertEquals(1, SarifReader.rank("note"));
        assertThrows(IllegalArgumentException.class, () -> SarifReader.read("{"));
    }
}
