# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""End-to-end checks of the opt-in change-policy gate through the real CLI jar and real Git repos.

Build the CLI first (mvn -pl aiv-cli -am package -DskipTests), then run:

    python scripts/e2e-change-policy.py

Prints one JSON line per scenario and ALL E2E OK on success; exits 1 otherwise. PR metadata is passed
the way the composite action passes it (AIV_PR_TITLE, AIV_PR_BODY, AIV_PR_LABELS,
AIV_PR_AUTHOR_ASSOCIATION).
"""
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

GATE = Path(__file__).resolve().parents[1]
JAR = GATE / "aiv-cli" / "target" / "aiv-cli-1.0.4.jar"

BASE = """schema_version: 1
gates:
  - id: density
    enabled: false
  - id: design
    enabled: false
  - id: dependency
    enabled: false
  - id: cohesion
    enabled: false
  - id: test-adequacy
    enabled: false
  - id: duplication
    enabled: false
{extra}
"""
POLICY = """  - id: change-policy
    enabled: true
    config:
      pack: {pack}
{body}
"""
PR_ENV = {"AIV_PR_TITLE": "Fix typo in scheduler", "AIV_PR_BODY": "Added 12 test cases for the loop.",
          "AIV_PR_AUTHOR_ASSOCIATION": "CONTRIBUTOR", "AIV_PR_LABELS": "[]"}


def git(repo, *args):
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", *args],
                   cwd=repo, check=True, capture_output=True)


def big_untested_change(repo):
    for i in range(12):
        (repo / "src" / f"m{i}.py").write_text("".join(f"v{j} = {j}\n" for j in range(25)), encoding="utf-8")
    (repo / ".github" / "workflows").mkdir(parents=True)
    (repo / ".github" / "workflows" / "ci.yml").write_text("on: push\n", encoding="utf-8")


def mass_change(repo):
    for i in range(1000):
        (repo / "src" / f"gen_{i}.py").write_text(f"v = {i}\n", encoding="utf-8")


def run(name, policy, env=None, message="Fix typo", change=None):
    repo = Path(tempfile.mkdtemp(prefix="aiv-cp-e2e-"))
    try:
        git(repo, "init", "-q", "-b", "main")
        (repo / ".aiv").mkdir()
        (repo / ".aiv" / "config.yaml").write_text(policy, encoding="utf-8")
        (repo / "src").mkdir()
        (repo / "src" / "app.py").write_text("x = 1\n", encoding="utf-8")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "base")
        base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, capture_output=True, text=True).stdout.strip()
        (change or big_untested_change)(repo)
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", message)
        out = repo / "report.json"
        clean_env = {k: v for k, v in os.environ.items() if not k.startswith("AIV_PR_")}
        p = subprocess.run(["java", "-jar", str(JAR), "--workspace", str(repo), "--diff", base, "--head", "HEAD",
                            "--require-trusted-config", "--quiet", "--output-json", str(out)],
                           capture_output=True, text=True, encoding="utf-8", env={**clean_env, **(env or {})})
        rep = json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}
        rules = sorted({(g["partition"], f["rule_id"]) for g in rep.get("gates", []) for f in g.get("findings", [])
                        if g["id"] == "change-policy"})
        msg = next((g.get("message", "") for g in rep.get("gates", []) if g["id"] == "change-policy"), "")
        res = {"exit": p.returncode, "rules": rules, "message": msg[:300],
               "stderr_tail": p.stderr[-300:] if p.returncode == 2 else ""}
        print(name, json.dumps(res))
        return res
    finally:
        shutil.rmtree(repo, ignore_errors=True)


def policy(pack="oss-maintainer", body=""):
    return BASE.format(extra=POLICY.format(pack=pack, body=body))


FAIL_UNTESTED = """      overrides:
        per_policy: { untested-source-change: [tests-not-applicable] }
      policies:
        - { id: untested-source-change, action: fail }"""

checks = [
    ("gate not configured: default off, no change-policy findings", run("1", BASE.format(extra=""), PR_ENV),
     lambda r: r["exit"] == 0 and r["rules"] == []),
    ("pack oss-maintainer with PR metadata: advisory findings, exit 0", run("2", policy(), PR_ENV),
     lambda r: r["exit"] == 0 and {("advisory", "policy.untested-source-change"), ("advisory", "policy.small-intent-large-diff"),
                                    ("advisory", "policy.infra-with-source"), ("advisory", "policy.ci-change-by-non-collaborator"),
                                    ("advisory", "policy.description-claims-mismatch")} <= set(r["rules"])),
    ("local run without PR metadata: metadata policies skipped, not faked", run("3", policy(), {}),
     lambda r: r["exit"] == 0 and set(r["rules"]) == {("advisory", "policy.untested-source-change"),
                                                       ("advisory", "policy.infra-with-source")}),
    ("promoted policy blocks; the other policies stay advisory", run("4", policy(body=FAIL_UNTESTED), PR_ENV),
     lambda r: r["exit"] == 1 and [x for x in r["rules"] if x[0] == "blocking"] == [("blocking", "policy.untested-source-change")]
     and ("advisory", "policy.infra-with-source") in r["rules"]),
    ("per-policy override label turns the block into a notice", run("5", policy(body=FAIL_UNTESTED),
                                                                    {**PR_ENV, "AIV_PR_LABELS": '["tests-not-applicable"]'}),
     lambda r: r["exit"] == 0 and any(x[1] == "change-policy.override" for x in r["rules"])
     and not any(x[0] == "blocking" for x in r["rules"])),
    ("airflow pack reads real git history: conventional-commit subject", run("6", policy("airflow"), PR_ENV, "fix: typo"),
     lambda r: r["exit"] == 0 and ("advisory", "policy.commit-hygiene") in r["rules"]),
    ("maintainer author: non-collaborator CI policy does not fire", run("7", policy(), {**PR_ENV, "AIV_PR_AUTHOR_ASSOCIATION": "MEMBER"}),
     lambda r: r["exit"] == 0 and not any("ci-change-by-non-collaborator" in x[1] for x in r["rules"])),
    ("1,000-file diff: mega-diff blocks by default", run("9", policy(), PR_ENV, change=mass_change),
     lambda r: r["exit"] == 1 and ("blocking", "policy.mega-diff") in r["rules"]),
    ("1,000-file diff with a maintainer override label: notice, exit 0",
     run("10", policy(body="      overrides: { labels: [mass-change-ok] }"), {**PR_ENV, "AIV_PR_LABELS": '["mass-change-ok"]'},
         change=mass_change),
     lambda r: r["exit"] == 0 and any(x[1] == "change-policy.override" for x in r["rules"])),
    ("invalid policy config: exit 2, never silent", run("8", policy(body="      policies: [ { id: x, when: { fact: nope, gt: 1 } } ]"), PR_ENV),
     lambda r: r["exit"] == 2 and "unknown fact" in r["stderr_tail"]),
]
bad = [name for name, res, ok in checks if not ok(res)]
print("ALL E2E OK" if not bad else f"E2E FAILED: {bad}")
raise SystemExit(1 if bad else 0)
