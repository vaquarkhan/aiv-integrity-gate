#!/usr/bin/env python3
"""Score the copied industry proof corpora with the local aiv-cli jar.

Each case uses the stored GitHub pull-request patch applied onto the file
bytes at the stored base SHA. If the base file cannot be fetched or git apply
fails, the case is unscored. It is not counted as a pass or a block.

The policy is the Airflow bench main .aiv tree (syntax, design, and invariant
fail; cohesion and density warn). Disclosure is not an expected failure.
"""
from __future__ import annotations

import argparse
import base64
import http.client
import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CORPUS = HERE / "corpus"
POLICY = HERE / "policy"
JAR = ROOT / "aiv-cli" / "target" / "aiv-cli-2.0.jar"
CACHE = HERE / "cache"
OUT = HERE / "results"

PROJECTS = (
    ("airflow", "apache/airflow", CORPUS / "airflow" / "cases.json", CORPUS / "airflow" / "prs"),
    ("flink", "apache/flink", CORPUS / "flink" / "cases.json", CORPUS / "flink" / "prs"),
    ("kafka", "apache/kafka", CORPUS / "kafka" / "cases.json", CORPUS / "kafka" / "prs"),
    ("curl", "curl/curl", CORPUS / "curl" / "cases.json", CORPUS / "curl" / "prs"),
)


def sh(args, cwd, timeout=180):
    p = subprocess.run(
        args, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout
    )
    return p.returncode, p.stdout, p.stderr


def label_of(case: dict) -> tuple[str, bool]:
    lab = case.get("label")
    if isinstance(lab, dict):
        name = lab.get("class") or lab.get("name") or "unlabeled"
        return str(name), bool(lab.get("expect_hard_fail"))
    if isinstance(lab, str) and lab:
        return lab, lab == "objective_slop"
    cohort = case.get("cohort") or "unlabeled"
    return str(cohort), False


def pr_number(case: dict) -> int | None:
    raw = case.get("pr", case.get("number"))
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


class Fetcher:
    def __init__(self, token: str):
        self.token = token
        self.stopped = ""

    def get(self, repo: str, path: str, sha: str) -> bytes | None:
        if self.stopped:
            return None
        dest = CACHE / repo.replace("/", "__") / sha / path
        if dest.is_file():
            return dest.read_bytes()
        quoted = urllib.parse.quote(path)
        url = f"https://api.github.com/repos/{repo}/contents/{quoted}?ref={sha}"
        req = urllib.request.Request(
            url,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "aiv-industry-e2e",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                remaining = resp.headers.get("X-RateLimit-Remaining")
                body = resp.read()
        except urllib.error.HTTPError as exc:
            if exc.code in (403, 429):
                self.stopped = f"github api {exc.code}"
            return None
        except (urllib.error.URLError, http.client.IncompleteRead, TimeoutError, OSError):
            return None
        if remaining is not None and remaining.isdigit() and int(remaining) < 25:
            self.stopped = "github api rate limit nearly exhausted"
        try:
            payload = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError:
            return None
        data = None
        if isinstance(payload, dict) and payload.get("encoding") == "base64" and payload.get("content"):
            try:
                data = base64.b64decode(payload["content"])
            except ValueError:
                return None
        elif isinstance(payload, dict) and payload.get("download_url"):
            try:
                dreq = urllib.request.Request(payload["download_url"], headers={"User-Agent": "aiv-industry-e2e"})
                with urllib.request.urlopen(dreq, timeout=60) as resp:
                    data = resp.read()
            except (urllib.error.URLError, http.client.IncompleteRead, TimeoutError, OSError):
                return None
        if data is None:
            return None
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        return data


def file_from_added_patch(patch: str) -> bytes:
    lines = []
    for line in patch.splitlines():
        if line.startswith(("+++", "---", "@@", "diff ")):
            continue
        if line.startswith("+"):
            lines.append(line[1:])
        elif line.startswith("-") or line.startswith("\\"):
            continue
        else:
            lines.append(line[1:] if line.startswith(" ") else line)
    text = "\n".join(lines)
    if lines:
        text += "\n"
    return text.encode("utf-8")


def git_patch(path: str, hunk: str) -> str:
    body = hunk if hunk.endswith("\n") else hunk + "\n"
    if body.startswith("diff --git ") or body.startswith("--- "):
        return body.replace("/dev/null", f"a/{path}")
    return f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n{body}"


def materialize(repo: str, pr: dict, work: Path, fetch: Fetcher) -> tuple[str | None, str]:
    files = pr.get("files") or []
    if not files:
        return None, "no files in stored patch record"
    base_sha = pr.get("base_sha")
    if not base_sha:
        return None, "stored record has no base_sha"
    if not POLICY.is_dir():
        return None, f"missing policy directory: {POLICY}"
    policy = work / ".aiv"
    shutil.copytree(POLICY, policy)
    code, _, err = sh(["git", "init", "-q"], cwd=work)
    if code != 0:
        return None, err.strip() or "git init failed"
    sh(["git", "config", "core.autocrlf", "false"], cwd=work)
    sh(["git", "config", "user.email", "bench@aiv.local"], cwd=work)
    sh(["git", "config", "user.name", "AIV Bench"], cwd=work)
    (work / "README.md").write_text(f"AIV industry e2e workspace for {repo}#{pr.get('number')}\n", encoding="utf-8")
    hunks = []
    added = []
    for f in files:
        path = (f.get("filename") or "").replace("\\", "/")
        status = f.get("status") or "modified"
        patch = f.get("patch") or ""
        if not path or f.get("patch_omitted") or f.get("patch_truncated"):
            return None, f"incomplete stored patch for {path or '?'}"
        if status != "added":
            src = f.get("previous_filename") or path
            blob = fetch.get(repo, src, base_sha)
            if blob is None:
                why = fetch.stopped or f"base file unavailable: {src}@{base_sha[:12]}"
                return None, why
            dest = work / src
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(blob)
        if status == "added":
            if not patch.strip():
                return None, f"stored patch empty for {path}"
            added.append((path, file_from_added_patch(patch)))
            continue
        if status == "removed":
            continue
        if not patch.strip():
            return None, f"stored patch empty for {path}"
        hunks.append(git_patch(path, patch))
    code, _, err = sh(["git", "add", "-A"], cwd=work)
    code, _, err = sh(["git", "commit", "-q", "-m", "base"], cwd=work)
    if code != 0:
        return None, (err or "base commit failed").strip()
    base = sh(["git", "rev-parse", "HEAD"], cwd=work)[1].strip()
    for path, content in added:
        dest = work / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(content)
    if hunks:
        patch_file = work / ".aiv-pr.patch"
        patch_file.write_text("".join(hunks), encoding="utf-8", newline="\n")
        code, _, err = sh(["git", "apply", "--whitespace=nowarn", str(patch_file)], cwd=work)
        patch_file.unlink(missing_ok=True)
        if code != 0:
            return None, "git apply failed: " + " ".join((err or "").split())[:300]
    for f in files:
        if f.get("status") == "removed":
            target = work / (f.get("filename") or "")
            if target.is_file():
                target.unlink()
    if not hunks and not any(f.get("status") in ("added", "removed") for f in files):
        return None, "no applicable hunks"
    sh(["git", "add", "-A"], cwd=work)
    code, _, err = sh(["git", "commit", "-q", "--allow-empty", "-m", "pr"], cwd=work)
    if code != 0:
        return None, (err or "head commit failed").strip()
    return base, ""


def score_case(repo: str, case: dict, prs: Path, fetch: Fetcher, jar: Path) -> dict:
    number = pr_number(case)
    label, expect = label_of(case)
    row = {
        "id": case.get("id") or f"{repo}-{number}",
        "project_repo": repo,
        "pr": number,
        "url": case.get("url"),
        "title": case.get("title"),
        "label": label,
        "expect_hard_fail": expect,
        "merged": case.get("merged"),
    }
    if number is None:
        row.update(verdict="UNSCORED", exit=None, reason="case has no pull request number")
        return row
    kind = case.get("kind")
    if kind and kind != "pull_request":
        row.update(verdict="UNSCORED", exit=None, reason=f"not a pull request ({kind})")
        return row
    stored = prs / f"{number}.json"
    if not stored.is_file():
        row.update(verdict="UNSCORED", exit=None, reason="stored patch file missing")
        return row
    pr = json.loads(stored.read_text(encoding="utf-8"))
    work = Path(tempfile.mkdtemp(prefix="aiv-e2e-"))
    try:
        base, reason = materialize(repo, pr, work, fetch)
        if base is None:
            row.update(verdict="UNSCORED", exit=None, reason=reason)
            return row
        report = work / "aiv.json"
        code, _, err = sh(
            ["java", "-jar", str(jar), "--workspace", str(work), "--diff", base, "--head", "HEAD",
             "--output-json", str(report), "--quiet"],
            cwd=work,
            timeout=180,
        )
        data = {}
        if report.is_file():
            try:
                data = json.loads(report.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                data = {}
        hard, soft, rules = [], [], []
        for g in data.get("gates") or []:
            if g.get("passed"):
                continue
            (hard if g.get("blocks_ci") else soft).append(g.get("id"))
            if g.get("blocks_ci"):
                for finding in g.get("findings") or []:
                    rules.append(finding.get("rule_id") or finding.get("ruleId") or g.get("id"))
        if code == 1:
            verdict = "BLOCK"
        elif code == 0:
            verdict = "PASS"
        else:
            verdict = "ERROR"
        row.update(
            verdict=verdict,
            exit=code,
            blocking_gates=hard,
            advisory_gates=soft,
            blocking_rules=rules,
            reason=(" ".join((err or "").split())[-300:] if verdict == "ERROR" else ""),
        )
        return row
    finally:
        shutil.rmtree(work, ignore_errors=True)


def write_summary(rows: list[dict], meta: dict) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "cases.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    by_project = {}
    for project in dict.fromkeys(r["project_repo"] for r in rows):
        subset = [r for r in rows if r["project_repo"] == project]
        classes = {}
        for label in dict.fromkeys(r["label"] for r in subset):
            group = [r for r in subset if r["label"] == label]
            classes[label] = {
                "n": len(group),
                "blocked": sum(1 for r in group if r["verdict"] == "BLOCK"),
                "passed": sum(1 for r in group if r["verdict"] == "PASS"),
                "unscored": sum(1 for r in group if r["verdict"] == "UNSCORED"),
                "error": sum(1 for r in group if r["verdict"] == "ERROR"),
                "blocking_rules": dict(Counter(rule for r in group if r["verdict"] == "BLOCK" for rule in r.get("blocking_rules") or [])),
            }
        by_project[project] = {
            "n": len(subset),
            "blocked": sum(1 for r in subset if r["verdict"] == "BLOCK"),
            "passed": sum(1 for r in subset if r["verdict"] == "PASS"),
            "unscored": sum(1 for r in subset if r["verdict"] == "UNSCORED"),
            "error": sum(1 for r in subset if r["verdict"] == "ERROR"),
            "by_label": classes,
        }
    summary = {
        "schema_version": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        **meta,
        "not_claimed": [
            "A filled Generated-by line or disclosure checkbox is not an expected gate failure.",
            "A block is a gate finding on the applied diff. It is not a label that the pull request is AI slop.",
            "Closed-unmerged is not AI slop.",
            "These samples are not the fraction of each project that is AI-authored.",
            "Unscored means the stored patch could not be applied to the base file. It is neither a pass nor a block.",
        ],
        "projects": by_project,
    }
    (OUT / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    lines = [
        "# Industry end-to-end run",
        "",
        f"Generated {summary['generated_at']}.",
        f"CLI `{meta['aiv_commit']}`, jar `{meta['jar']}`.",
        f"Policy: Airflow bench `main` `.aiv/config.yaml` at `{meta['policy_commit']}`.",
        "",
        "Each scored case is the stored pull-request patch applied onto the file at the stored base SHA.",
        "Unscored cases are omitted from blocked and passed.",
        "",
        "| project | n | blocked | passed | unscored | error |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for name, proj in by_project.items():
        lines.append(f"| {name} | {proj['n']} | {proj['blocked']} | {proj['passed']} | {proj['unscored']} | {proj['error']} |")
    lines += ["", "## By label", ""]
    for name, proj in by_project.items():
        lines.append(f"### {name}")
        lines.append("")
        lines.append("| label | n | blocked | passed | unscored | error |")
        lines.append("|---|---:|---:|---:|---:|---:|")
        for label, g in proj["by_label"].items():
            lines.append(f"| {label} | {g['n']} | {g['blocked']} | {g['passed']} | {g['unscored']} | {g['error']} |")
        lines.append("")
    lines += ["## Not claimed", ""]
    for item in summary["not_claimed"]:
        lines.append(f"- {item}")
    lines.append("")
    (OUT / "RESULTS.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--project", default="")
    args = ap.parse_args()
    if not JAR.is_file():
        raise SystemExit(f"missing {JAR}")
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        token = subprocess.check_output(["gh", "auth", "token"], text=True).strip()
    fetch = Fetcher(token)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    policy_commit = subprocess.check_output(
        ["git", "-C", r"C:\Users\Administrator\Downloads\aiv-airflow-bench", "rev-parse", "origin/main"],
        text=True,
    ).strip()
    rows = []
    for project, repo, cases_path, prs in PROJECTS:
        if args.project and args.project != project:
            continue
        doc = json.loads(cases_path.read_text(encoding="utf-8"))
        cases = doc["cases"] if isinstance(doc, dict) else doc
        if args.limit:
            cases = cases[: args.limit]
        print(f"{project}: {len(cases)} cases", flush=True)
        for i, case in enumerate(cases, 1):
            if fetch.stopped and rows and rows[-1].get("reason") == fetch.stopped:
                rows.append({
                    "id": case.get("id"),
                    "project_repo": repo,
                    "pr": pr_number(case),
                    "url": case.get("url"),
                    "title": case.get("title"),
                    "label": label_of(case)[0],
                    "expect_hard_fail": label_of(case)[1],
                    "verdict": "UNSCORED",
                    "exit": None,
                    "reason": fetch.stopped,
                })
                continue
            row = score_case(repo, case, prs, fetch, JAR)
            rows.append(row)
            print(f"[{project} {i}/{len(cases)}] {row['id']}: {row['verdict']} {row.get('reason','')}", flush=True)
            if fetch.stopped:
                print(f"stopped: {fetch.stopped}", flush=True)
    write_summary(rows, {
        "aiv_commit": commit,
        "policy_commit": policy_commit,
        "jar": str(JAR),
        "policy": "aiv-airflow-bench origin/main .aiv/config.yaml and design-rules.yaml",
    })
    print(f"wrote {OUT / 'RESULTS.md'}", flush=True)


if __name__ == "__main__":
    main()
