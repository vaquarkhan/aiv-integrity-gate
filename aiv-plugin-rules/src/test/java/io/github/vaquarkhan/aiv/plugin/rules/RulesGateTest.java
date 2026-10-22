/*
 * Copyright 2026 Vaquar Khan
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.vaquarkhan.aiv.plugin.rules;

import io.github.vaquarkhan.aiv.model.AIVConfig;
import io.github.vaquarkhan.aiv.model.AIVContext;
import io.github.vaquarkhan.aiv.model.ChangedFile;
import io.github.vaquarkhan.aiv.model.Diff;
import io.github.vaquarkhan.aiv.model.Finding;
import io.github.vaquarkhan.aiv.model.GateResult;
import io.github.vaquarkhan.aiv.port.QualityGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesGateTest {

    static final String FLAWED = String.join("\n",
            "from datetime import datetime",
            "from airflow import DAG",
            "from airflow.operators.bash_operator import BashOperator",
            "from airflow.sensors.filesystem import FileSensor",
            "from airflow.models import BaseOperator",
            "",
            "with DAG(",
            "    dag_id=\"example_pipeline\",",
            "    start_date=datetime.now(),",
            "    schedule=\"@daily\",",
            ") as dag:",
            "    t1 = BashOperator(task_id=\"t1\", bash_command=\"echo TODO implement\")",
            "    wait = FileSensor(task_id=\"wait\", filepath=\"/tmp/x\", fs_conn_id=\"fs_prod\")",
            "",
            "class MyOp(BaseOperator):",
            "    def execute(self, context):",
            "        \"\"\"Docstring.\"\"\"",
            "        # not yet",
            "        pass",
            "");

    static final String CLEAN = String.join("\n",
            "import pendulum",
            "from airflow import DAG",
            "from airflow.providers.standard.operators.bash import BashOperator",
            "from airflow.sensors.filesystem import FileSensor",
            "",
            "with DAG(",
            "    dag_id=\"billing_daily\",",
            "    start_date=pendulum.datetime(2024, 1, 1, tz=\"UTC\"),",
            "    catchup=False,",
            ") as dag:",
            "    t1 = BashOperator(task_id=\"t1\", bash_command=\"python etl.py\")",
            "    wait = FileSensor(task_id=\"wait\", filepath=\"/tmp/x\", timeout=600, fs_conn_id=\"fs_default\")",
            "");

    private static AIVContext ctx(Path ws, Map<String, Object> cfg, String rawDiff, ChangedFile... files) {
        AIVConfig config = new AIVConfig(List.of(new AIVConfig.GateConfig(RulesGate.ID, true, cfg)), Map.of());
        return new AIVContext(ws, new Diff("main", "HEAD", List.of(files), rawDiff), config);
    }

    private static Set<String> ruleIds(GateResult r) {
        return r.getFindings().stream().map(Finding::getRuleId).collect(Collectors.toSet());
    }

    @Test
    void registeredViaServiceLoader() {
        assertTrue(ServiceLoader.load(QualityGate.class).stream().anyMatch(p -> p.type() == RulesGate.class));
        assertEquals("rules", new RulesGate().getId());
    }

    @Test
    void airflowPackFindsEveryDefectAndIsQuietOnCleanDag(@TempDir Path ws) {
        Map<String, Object> cfg = Map.of("packs", List.of("airflow"), "params", Map.of("known_conn_ids", List.of("fs_default")));
        GateResult r = new RulesGate().evaluate(ctx(ws, cfg, "",
                new ChangedFile("dags/flawed.py", ChangedFile.ChangeType.ADDED, FLAWED)));
        assertFalse(r.isPassed());
        assertTrue(r.blocksCi());
        assertEquals(Set.of("rules.airflow.start-date-now", "rules.airflow.missing-catchup", "rules.airflow.example-dag-id",
                "rules.airflow.sensor-no-timeout", "rules.airflow.hollow-execute", "rules.airflow.placeholder-task",
                "rules.airflow.removed-import", "rules.airflow.unknown-conn-id"), ruleIds(r));
        Finding catchup = r.getFindings().stream().filter(f -> f.getRuleId().endsWith("missing-catchup")).findFirst().get();
        assertEquals(7, catchup.getStartLine());
        assertTrue(r.getMessage().contains("dag_id 'example_pipeline'"));
        assertTrue(r.getMessage().contains("FileSensor has no timeout="));
        assertTrue(r.getMessage().contains("execute() has no implementation"));

        GateResult clean = new RulesGate().evaluate(ctx(ws, cfg, "",
                new ChangedFile("dags/clean.py", ChangedFile.ChangeType.ADDED, CLEAN)));
        assertTrue(clean.isPassed(), clean.getMessage());
    }

    @Test
    void addedLinesScopingAndDisabledRules(@TempDir Path ws) {
        String diff = "diff --git a/dags/flawed.py b/dags/flawed.py\n--- a/dags/flawed.py\n+++ b/dags/flawed.py\n@@ -1 +1 @@\n"
                + "+    start_date=datetime.now(),\n+    t1 = BashOperator(task_id=\"t1\", bash_command=\"echo TODO implement\")\n"
                + "diff --git a/x b/x\n--- a/x\n+++ /dev/null\n+gone\n";
        Map<String, Object> cfg = Map.of("packs", List.of("airflow"),
                "disabled_rules", List.of("rules.airflow.placeholder-task", "AIRFLOW.removed-import"));
        GateResult r = new RulesGate().evaluate(ctx(ws, cfg, diff,
                new ChangedFile("dags\\flawed.py", ChangedFile.ChangeType.MODIFIED, FLAWED),
                new ChangedFile("README.md", ChangedFile.ChangeType.MODIFIED, "from airflow import DAG\nstart_date=datetime.now()"),
                new ChangedFile("plain.py", ChangedFile.ChangeType.ADDED, "start_date=datetime.now()\n")));
        assertEquals(Set.of("rules.airflow.start-date-now"), ruleIds(r));
        assertEquals(Set.of("dags/flawed.py"), RulesGate.addedLines(diff).keySet());
        assertTrue(RulesGate.addedLines(null).isEmpty());
        assertEquals("", RulesGate.norm(null));
    }

    @Test
    void noRulesConfiguredPassesWithNotice(@TempDir Path ws) {
        GateResult r = new RulesGate().evaluate(ctx(ws, Map.of(), "", new ChangedFile("a.py", ChangedFile.ChangeType.ADDED, "x")));
        assertTrue(r.isPassed());
        assertEquals("No rules configured", r.getMessage());
        GateResult none = new RulesGate().evaluate(new AIVContext(ws, new Diff("m", "h", List.of(), ""), new AIVConfig(List.of(), Map.of())));
        assertTrue(none.isPassed());
    }

    @Test
    void projectRuleFilesInlineRulesAndSeverity(@TempDir Path ws) throws IOException {
        Files.createDirectories(ws.resolve(".aiv/rules"));
        Files.writeString(ws.resolve(".aiv/rules/team.yaml"), String.join("\n",
                "rules:",
                "  - id: no-print",
                "    pattern: \"\\\\bprint\\\\(\"",
                "    severity: warn",
                "    message: print in production code",
                "  - id: hollow-handler",
                "    kind: hollow-function",
                "    function: \"handle\\\\w*\"",
                "    paths: [\"**/*.java\", \"src/*.kt\"]",
                "    message: \"{value} is hollow\""));
        Files.writeString(ws.resolve("other.yaml"), "rules:\n  - id: plain\n    pattern: zzz\n");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("rules_files", List.of(".aiv/rules/*.yaml", "other.yaml"));
        cfg.put("rules", List.of(Map.of("id", "no-todo", "pattern", "TODO", "message", "todo left", "paths", List.of("**/*.py"))));
        String java = "class H {\n  public void handleEvent(Event e) {\n    // TODO\n    return;\n  }\n  public void handleOk() {\n    doWork();\n  }\n}\n";
        GateResult r = new RulesGate().evaluate(ctx(ws, cfg, "",
                new ChangedFile("src/main/H.java", ChangedFile.ChangeType.ADDED, java),
                new ChangedFile("app.py", ChangedFile.ChangeType.ADDED, "print('x')  # TODO\n")));
        assertEquals(Set.of("rules.no-print", "rules.hollow-handler", "rules.no-todo"), ruleIds(r));
        assertTrue(r.blocksCi());
        assertTrue(r.getMessage().contains("handleEvent is hollow"));

        Map<String, Object> warnOnly = Map.of("rules", List.of(Map.of("id", "p", "pattern", "print", "severity", "warn")));
        GateResult adv = new RulesGate().evaluate(ctx(ws, warnOnly, "", new ChangedFile("app.py", ChangedFile.ChangeType.ADDED, "print(1)")));
        assertFalse(adv.isPassed());
        assertFalse(adv.blocksCi());
        assertTrue(adv.getMessage().contains("rule p matched"));
    }

    @Test
    void readsRulesFromFrozenPolicyWorkspace(@TempDir Path ws) throws Exception {
        Path source = ws.resolve("source");
        Path policy = ws.resolve("policy");
        Files.createDirectories(source);
        Files.createDirectories(policy.resolve(".aiv/rules"));
        Files.writeString(policy.resolve(".aiv/rules/team.yaml"), """
                rules:
                  - id: frozen-rule
                    pattern: forbiddenValue
                """);
        Map<String, Object> gateConfig = Map.of("rules_files", List.of(".aiv/rules/*.yaml"));
        AIVConfig config = new AIVConfig(List.of(new AIVConfig.GateConfig("rules", true, gateConfig)), Map.of());
        Diff diff = new Diff("base", "head", List.of(new ChangedFile("A.java",
                ChangedFile.ChangeType.ADDED, "class A { String x = forbiddenValue; }")), "");
        GateResult result = new RulesGate().evaluate(new AIVContext(source, policy, diff, config, Map.of()));
        assertFalse(result.isPassed());
        assertEquals("rules.frozen-rule", result.getFindings().get(0).getRuleId());
    }

    @Test
    void packErrorsAndFileErrors(@TempDir Path ws) {
        RulesGate gate = new RulesGate();
        assertThrows(IllegalArgumentException.class, () -> gate.evaluate(ctx(ws, Map.of("packs", List.of("nope")), "")));
        assertThrows(IllegalArgumentException.class, () -> gate.evaluate(ctx(ws, Map.of("packs", List.of("../etc")), "")));
        RulesGate failing = new RulesGate(n -> { throw new IOException("disk"); });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> failing.evaluate(ctx(ws, Map.of("packs", List.of("airflow")), "")));
        assertTrue(e.getMessage().contains("disk"));
        assertThrows(IllegalArgumentException.class,
                () -> gate.evaluate(ctx(ws.resolve("does-not-exist"), Map.of("rules_files", List.of("*.yaml")), "")));
        assertThrows(IllegalArgumentException.class,
                () -> gate.evaluate(ctx(ws, Map.of("rules_files", List.of("missing.yaml")), "")));
    }

    @Test
    void unreadableRuleFileIsReported(@TempDir Path ws) throws IOException {
        Files.createDirectory(ws.resolve("dir.yaml"));
        // a directory is a "regular file" for neither branch; use a glob that matches a directory name via walk filter
        Files.writeString(ws.resolve("ok.yaml"), "rules: []\n");
        assertTrue(new RulesGate().loadRules(Map.of("rules_files", List.of("*.yaml")), Map.of(), ws).isEmpty());
        Path bad = ws.resolve("bad.yaml");
        Files.write(bad, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertThrows(IllegalArgumentException.class,
                () -> new RulesGate().loadRules(Map.of("rules_files", List.of("bad.yaml")), Map.of(), ws));
    }

    @Test
    void loaderValidation() {
        Map<String, Object> p = Map.of();
        assertEquals(Map.of(), RuleLoader.parseYaml(null));
        assertEquals(Map.of(), RuleLoader.parseYaml(""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.parseYaml("- a\n- b"));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compile(Map.of("rules", List.of("x")), p, null));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compile(Map.of("rules",
                List.of(Map.of("id", "a", "pattern", "x"), Map.of("id", "a", "pattern", "y"))), p, null));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("pattern", "x"), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "kind", "magic"), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "pattern", "("), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "pattern", "${nope}"), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "kind", "pattern"), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "kind", "call"), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "kind", "call", "call", "f"), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "kind", "hollow-function"), p, ""));
        assertThrows(IllegalArgumentException.class, () -> RuleLoader.compileRule(Map.of("id", "a", "pattern", "(x)", "capture_group", 1), p, ""));

        Rule r = RuleLoader.compileRule(Map.of("id", "a", "pattern", "(x)", "allowed", List.of("x", ""), "added_lines_only", false,
                "message", "${who} ${list} ${raw|raw}"), Map.of("who", "me", "list", List.of("a.b"), "raw", List.of("a.b", "c")), "pk.");
        assertEquals("pk.a", r.id());
        assertFalse(r.addedLinesOnly());
        assertEquals(Set.of("x"), r.allowed());
        assertEquals("me (?:\\Qa.b\\E) (?:(?:a.b)|(?:c))", r.message());
        assertTrue(r.appliesTo("anything"));
        Rule empty = RuleLoader.compileRule(Map.of("id", "b", "pattern", "(x)", "allowed_param", "ids"), Map.of("ids", List.of()), "");
        assertTrue(empty.allowed().isEmpty());
        assertEquals("(?!)", RuleLoader.str("${ids}", Map.of("ids", List.of())));
        assertNull(RuleLoader.str(null, p));
        assertEquals(List.of("one"), RuleLoader.strings("one", p));
        assertEquals(List.of(), RuleLoader.strings(null, p));
        Map<String, Object> doc = RuleLoader.parseYaml("name: ' '\nrules:\n  - id: a\n    pattern: x\n");
        assertEquals("a", RuleLoader.compile(doc, null, "fallback").get(0).id());
        assertEquals("fb.a", RuleLoader.compile(Map.of("rules", List.of(Map.of("id", "a", "pattern", "x"))), null, "fb").get(0).id());
    }

    @Test
    void globsAndPaths() {
        assertTrue(Globs.compile("**/*.py").matcher("a/b/c.py").matches());
        assertTrue(Globs.compile("**/*.py").matcher("c.py").matches());
        assertFalse(Globs.compile("src/*.py").matcher("src/a/b.py").matches());
        assertTrue(Globs.compile("src/**").matcher("src/a/b.py").matches());
        assertTrue(Globs.compile("a?c.txt").matcher("abc.txt").matches());
        assertFalse(Globs.compile("a?c.txt").matcher("a/c.txt").matches());
        assertTrue(Globs.compile("dags\\x.py").matcher("dags/x.py").matches());
        Rule r = RuleLoader.compileRule(Map.of("id", "a", "pattern", "x", "paths", List.of("**/*.py")), Map.of(), "");
        assertTrue(r.appliesTo("d/x.py"));
        assertFalse(r.appliesTo("d/x.java"));
    }

    @Test
    void engineEdgeCases() {
        Map<String, Object> p = Map.of();
        Rule zeroWidth = RuleLoader.compileRule(Map.of("id", "zw", "pattern", "^"), p, "");
        assertEquals(1, RuleEngine.scan("f.txt", "abc", null, List.of(zeroWidth)).size());
        Rule highGroup = RuleLoader.compileRule(Map.of("id", "hg", "pattern", "(a)", "capture_group", 5, "capture_matches", "a"), p, "");
        assertEquals("rule hg matched", RuleEngine.scan("f.txt", "a", null, List.of(highGroup)).get(0).message());
        Rule nullGroup = RuleLoader.compileRule(Map.of("id", "ng", "pattern", "(x)|(y)", "capture_group", 1, "capture_matches", "y"), p, "");
        assertEquals(1, RuleEngine.scan("f.txt", "y", null, List.of(nullGroup)).size());

        Rule call = RuleLoader.compileRule(Map.of("id", "c", "kind", "call", "call", "DAG", "if_arg", "start_date", "require_arg", "catchup"), p, "");
        assertTrue(RuleEngine.scan("f.py", "DAG(x=1)", null, List.of(call)).isEmpty());
        assertTrue(RuleEngine.scan("f.py", "DAG(start_date=1,", null, List.of(call)).isEmpty());
        assertTrue(RuleEngine.scan("f.py", "foo()", null, List.of(call)).isEmpty());
        assertEquals(1, RuleEngine.scan("f.py", "DAG(\n start_date=1\n)", null, List.of(call)).size());
        Rule cap = RuleLoader.compileRule(Map.of("id", "k", "kind", "call", "call", "DAG", "capture", "id=(\\w+)", "allowed", List.of("ok")), p, "");
        assertTrue(RuleEngine.scan("f.py", "DAG(x=1)", null, List.of(cap)).isEmpty());
        assertTrue(RuleEngine.scan("f.py", "DAG(id=ok)", null, List.of(cap)).isEmpty());
        assertEquals("rule k matched", RuleEngine.scan("f.py", "DAG(id=bad)", null, List.of(cap)).get(0).message());
        java.util.regex.Matcher nm = java.util.regex.Pattern.compile("q").matcher("q");
        nm.find();
        assertEquals("q", RuleEngine.firstGroup(nm));
        assertNull(RuleEngine.callText(new String[] {"f("}, 0, 0));
        assertEquals("", RuleEngine.render("{value}", null));

        Rule hollow = RuleLoader.compileRule(Map.of("id", "h", "kind", "hollow-function", "function", "run"), p, "");
        assertTrue(RuleEngine.scan("f.py", "def run(self):\n    x = 1\n", null, List.of(hollow)).isEmpty());
        assertTrue(RuleEngine.scan("f.py", "def run(self):\n", null, List.of(hollow)).isEmpty());
        assertTrue(RuleEngine.scan("f.py", "def other(self):\n    pass\n", null, List.of(hollow)).isEmpty());
        assertEquals(1, RuleEngine.scan("f.py", "async def run(self) -> None:  # c\n    '''doc\n    more'''\n    ...\ndef z():\n    pass", null, List.of(hollow)).size());
        assertEquals(1, RuleEngine.scan("f.py", "def run(self):\n    \"\"\"\n    doc\n    \"\"\"\n    raise NotImplementedError()\n", null, List.of(hollow)).size());
        assertEquals(1, RuleEngine.scan("f.py", "class A:\n    def run(self):\n        return None\n    def b(self):\n        pass", null, List.of(hollow)).size());
        assertEquals(1, RuleEngine.scan("f.java", "void run() {\n  throw new UnsupportedOperationException(\"x\");\n}\n", null, List.of(hollow)).size());
        assertEquals(1, RuleEngine.scan("f.go", "func run() {\n\n  panic(\"todo\")\n}\n", null, List.of(hollow)).size());
        assertTrue(RuleEngine.scan("f.java", "void run() {\n  work();\n}\n", null, List.of(hollow)).isEmpty());
        assertTrue(RuleEngine.scan("f.java", "void run() {\n  return;\n", null, List.of(hollow)).isEmpty());
        assertTrue(RuleEngine.scan("f.java", "void run() {\n  // only a comment\n}\n", null, List.of(hollow)).isEmpty());
        assertEquals(1, RuleEngine.scan("f.java", "public String run(int a) {\n  {\n  }\n  return null;\n}\n", null, List.of(hollow)).size());
        assertTrue(RuleEngine.scan("f.java", "public String run(int a) {\n  if (a) {\n  }\n  return null;\n}\n", null, List.of(hollow)).isEmpty());
        assertTrue(RuleEngine.scan("f.java", "int run = 1;\n", null, List.of(hollow)).isEmpty());

        Rule fireAll = RuleLoader.compileRule(Map.of("id", "fa", "pattern", "id=(\\w+)", "allowed_param", "ids",
                "skip_when_allowed_empty", false), Map.of("ids", List.of()), "");
        assertEquals(1, RuleEngine.scan("f.py", "id=any", null, List.of(fireAll)).size());
    }
}
