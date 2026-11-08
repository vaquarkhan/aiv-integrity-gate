# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
from __future__ import annotations

import importlib.util
import json
import urllib.request
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("aiv_review_ledger", Path(__file__).with_name("aiv_review_ledger.py"))
L = importlib.util.module_from_spec(spec)
spec.loader.exec_module(L)

A, B, C = "a" * 40, "b" * 40, "c" * 40


def report(*findings: tuple[str, str, str], passed: bool = True) -> dict:
    gates = {}
    for rule, part, file in findings:
        gid = rule.split(".")[0]
        g = gates.setdefault((gid, part), {"id": gid, "partition": part, "findings": []})
        g["findings"].append({"rule_id": rule, "file": file})
    return {"schema_version": 3, "passed": passed,
            "gates": [{"id": "review-routing", "partition": "pass", "findings": [{"rule_id": "review-routing.tier"}]},
                      *gates.values()]}


def write(tmp: Path, name: str, obj) -> Path:
    p = tmp / name
    p.write_text(json.dumps(obj), encoding="utf-8")
    return p


def test_findings_extraction_skips_passed_gates_and_requires_schema_3():
    r = report(("invariant.placeholder", "advisory", "a.py"), ("syntax.parse", "blocking", "b.py"))
    r["gates"].append({"id": "design", "partition": "advisory", "findings": []})
    got = L.aiv_findings(r)
    assert {"rule": "invariant.placeholder", "partition": "advisory", "file": "a.py"} in got
    assert {"rule": "syntax.parse", "partition": "blocking", "file": "b.py"} in got
    assert {"rule": "design.gate", "partition": "advisory", "file": "(run)"} in got
    assert all(f["rule"] != "review-routing.tier" for f in got)
    with pytest.raises(SystemExit):
        L.aiv_findings({"schema_version": 2})


def test_ai_comment_extraction_and_sanitising():
    raw = [
        {"id": 1, "user": {"login": "copilot-pull-request-reviewer[bot]"}, "path": "a.py", "position": None,
         "line": None, "reactions": {"-1": 0, "+1": 2}, "body": "Possible None deref\nmore"},
        {"id": 2, "user": {"login": "CodeRabbitAI[bot]"}, "path": "b.py", "position": 3, "line": 10,
         "reactions": {"-1": 1}, "body": "x |y\u200b" + "z" * 300},
        {"id": 3, "user": {"login": "maintainer"}, "path": "c.py", "body": "human"},
        {"id": 4, "user": None, "body": "ghost"},
    ]
    got = L.ai_comments(raw, L.DEFAULT_AI_REVIEWERS)
    assert [c["id"] for c in got] == [1, 2]
    assert got[0]["outdated"] and got[0]["upvoted"] and not got[0]["downvoted"]
    assert got[0]["head"] == "Possible None deref"
    assert not got[1]["outdated"] and got[1]["downvoted"]
    assert "|" not in got[1]["head"] and "\u200b" not in got[1]["head"] and got[1]["head"].endswith("...")


def test_wilson_bounds():
    assert L.wilson(0, 0) == (0.0, 1.0)
    lo, hi = L.wilson(10, 10)
    assert 0.69 < lo < 0.73 and hi == pytest.approx(1.0)
    lo, hi = L.wilson(0, 20)
    assert lo == 0.0 and 0.15 < hi < 0.17


def _ledger(tmp: Path) -> Path:
    ledger = tmp / "ledger.jsonl"
    # 12 merged PRs where an advisory placeholder appeared then was fixed: promotion candidate.
    for pr in range(1, 13):
        L.main(["record-aiv", "--ledger", str(ledger), "--pr", str(pr), "--sha", A,
                "--aiv-report", str(write(tmp, f"r{pr}a.json", report(("invariant.placeholder", "advisory", "a.py"))))])
        L.main(["record-aiv", "--ledger", str(ledger), "--pr", str(pr), "--sha", B,
                "--aiv-report", str(write(tmp, f"r{pr}b.json", report()))])
        L.main(["record-final", "--ledger", str(ledger), "--pr", str(pr), "--sha", B, "--merged", "true"])
    # 20 merged PRs that kept an advisory density note: demotion candidate. (With 0 of 12 the 95% upper
    # bound is still about 0.24, above the 0.2 threshold, so 12 would honestly not be enough evidence.)
    for pr in range(100, 120):
        L.main(["record-aiv", "--ledger", str(ledger), "--pr", str(pr), "--sha", C,
                "--aiv-report", str(write(tmp, f"d{pr}.json", report(("density.low", "advisory", "d.py"))))])
        L.main(["record-final", "--ledger", str(ledger), "--pr", str(pr), "--sha", C, "--merged", "yes"])
    # A PR merged while a blocking rule was present, one closed unmerged, one merged with no final run.
    L.main(["record-aiv", "--ledger", str(ledger), "--pr", "40", "--sha", A,
            "--aiv-report", str(write(tmp, "x.json", report(("invariant.merge-conflict", "blocking", "x.py"), passed=False)))])
    L.main(["record-final", "--ledger", str(ledger), "--pr", "40", "--sha", A, "--merged", "1"])
    L.main(["record-aiv", "--ledger", str(ledger), "--pr", "41", "--sha", A,
            "--aiv-report", str(write(tmp, "y.json", report(("syntax.parse", "blocking", "y.py"), passed=False)))])
    L.main(["record-final", "--ledger", str(ledger), "--pr", "41", "--sha", A, "--merged", "false"])
    L.main(["record-final", "--ledger", str(ledger), "--pr", "42", "--sha", C, "--merged", "true"])
    # AI comments on PR 1: one outdated on a file AIV flagged, one outdated elsewhere, one downvoted.
    comments = [
        {"id": 7, "user": {"login": "copilot-pull-request-reviewer[bot]"}, "path": "a.py", "position": None, "line": None},
        {"id": 8, "user": {"login": "copilot-pull-request-reviewer[bot]"}, "path": "z.py", "position": None,
         "line": None, "body": "Off-by-one in the loop bound"},
        {"id": 9, "user": {"login": "copilot-pull-request-reviewer[bot]"}, "path": "q.py", "position": None,
         "line": None, "reactions": {"-1": 1}, "body": "wrong"},
    ]
    L.main(["record-ai", "--ledger", str(ledger), "--pr", "1", "--comments", str(write(tmp, "c.json", comments))])
    L.main(["record-ai", "--ledger", str(ledger), "--pr", "1", "--comments", str(write(tmp, "c.json", comments))])
    return ledger


def test_report_proposes_promotion_demotion_and_flags_bypass(tmp_path, capsys):
    ledger = _ledger(tmp_path)
    a = L.analyse(L.read_rows([ledger]))
    by = {r["rule"]: r for r in a["rules"]}
    assert by["invariant.placeholder"]["acted_on"] == 12
    assert by["invariant.placeholder"]["proposal"].startswith("candidate: promote")
    assert by["density.low"]["merged_with"] == 20
    assert by["density.low"]["proposal"].startswith("candidate: turn off")
    twelve = L.analyse([r for r in L.read_rows([ledger]) if not (r["pr"] >= 112 and r["pr"] < 120)])
    assert {r["rule"]: r for r in twelve["rules"]}["density.low"]["proposal"] == "", "0/12 is not enough evidence"
    assert by["invariant.merge-conflict"]["proposal"].startswith("investigate")
    assert "syntax.parse" not in by, "closed unmerged PRs are not evidence"
    assert a["merged_prs"] == 34 and a["merged_prs_without_final_run"] == 1
    (rev,) = a["ai_reviewers"]
    assert rev["comments"] == 3, "re-recorded comments are de-duplicated by id"
    assert (rev["outdated"], rev["downvoted"]) == (3, 1)
    assert a["ai_findings_beyond_aiv_total"] == 1
    assert a["ai_findings_beyond_aiv"][0]["path"] == "z.py"

    small = L.analyse(L.read_rows([ledger]), min_prs=50)
    assert all(r["proposal"] == "" or r["proposal"].startswith("investigate") for r in small["rules"])

    assert L.main(["report", "--ledger", str(ledger)]) == 0
    md = capsys.readouterr().out
    assert md.startswith("# AIV review ledger report")
    assert "| `invariant.placeholder` | advisory | 12 | 12 | 0 |" in md
    assert "Off-by-one in the loop bound" in md
    assert "\u2014" not in md and "\u2013" not in md
    out = tmp_path / "r.json"
    L.main(["report", "--ledger", str(tmp_path), "--format", "json", "--output", str(out)])
    assert json.loads(out.read_text(encoding="utf-8"))["merged_prs"] == 34


def test_bad_inputs_fail_loudly(tmp_path):
    bad = tmp_path / "bad.jsonl"
    bad.write_text('{"schema": 1, "kind": "final"}\nnot json\n', encoding="utf-8")
    with pytest.raises(SystemExit):
        L.read_rows([bad])
    bad.write_text('\n{"schema": 9}\n', encoding="utf-8")
    with pytest.raises(SystemExit):
        L.read_rows([bad])
    with pytest.raises(SystemExit):
        L.main(["record-final", "--ledger", str(bad), "--pr", "1", "--sha", "not-a-sha", "--merged", "true"])
    with pytest.raises(SystemExit):
        L.main(["record-final", "--ledger", str(bad), "--pr", "1", "--sha", A, "--merged", "maybe"])


def test_collect_ai_uses_github_rest_with_pagination(tmp_path, monkeypatch):
    pages = {1: [{"id": i, "user": {"login": "coderabbitai[bot]"}, "path": "a.py", "position": 1, "line": 1}
                 for i in range(100)],
             2: [{"id": 500, "user": {"login": "coderabbitai[bot]"}, "path": "b.py", "position": None, "line": None}]}
    seen = []

    class Resp:
        def __init__(self, body):
            self.body = body

        def read(self):
            return self.body

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    def fake_urlopen(req, timeout):
        seen.append((req.full_url, req.headers.get("Authorization")))
        page = int(req.full_url.rsplit("page=", 1)[1])
        return Resp(json.dumps(pages[page]).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setenv("GITHUB_TOKEN", "tok")
    ledger = tmp_path / "l.jsonl"
    L.main(["collect-ai", "--ledger", str(ledger), "--pr", "5", "--repo", "apache/airflow"])
    row = json.loads(ledger.read_text(encoding="utf-8"))
    assert len(row["comments"]) == 101
    assert seen[0] == ("https://api.github.com/repos/apache/airflow/pulls/5/comments?per_page=100&page=1", "Bearer tok")
    with pytest.raises(SystemExit):
        L.main(["collect-ai", "--ledger", str(ledger), "--pr", "5", "--repo", "bad repo"])
    monkeypatch.delenv("GITHUB_TOKEN")
    with pytest.raises(SystemExit):
        L.main(["collect-ai", "--ledger", str(ledger), "--pr", "5", "--repo", "apache/airflow"])


def test_clean_handles_none():
    assert L.clean(None) == ""
