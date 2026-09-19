#!/usr/bin/env python3
"""Inject specific apache/airflow PR numbers into aiv-airflow-bench."""
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from pathlib import Path

REPO = "vaquarkhan/aiv-airflow-bench"
PRS = [73124, 70250]


def run(cmd, cwd=None):
    return subprocess.check_output(cmd, cwd=cwd, text=True, encoding="utf-8", errors="replace").strip()


def content_from_patch(patch):
    if not patch:
        return None
    lines = []
    for line in patch.splitlines():
        if line.startswith(("+++", "---", "@@")):
            continue
        if line.startswith("+"):
            lines.append(line[1:])
        elif line.startswith("-") or line.startswith("\\"):
            continue
        else:
            lines.append(line[1:] if line.startswith(" ") else line)
    return "\n".join(lines) + ("\n" if lines else "")


def main():
    tmp = Path(tempfile.mkdtemp(prefix="aiv-inject-specific-"))
    try:
        run(["gh", "repo", "clone", REPO, str(tmp), "--", "--depth", "1"])
        for n in PRS:
            branch = f"snapshot/airflow-pr-{n}"
            print(f"Injecting #{n}", flush=True)
            run(["git", "checkout", "main", "-q"], cwd=tmp)
            run(["git", "checkout", "-B", branch], cwd=tmp)
            meta = json.loads(run(["gh", "api", f"repos/apache/airflow/pulls/{n}"]))
            prov = tmp / "provenance" / f"airflow-pr-{n}"
            prov.mkdir(parents=True, exist_ok=True)
            (prov / "origin.json").write_text(
                json.dumps(
                    {
                        "origin_url": f"https://github.com/apache/airflow/pull/{n}",
                        "title": meta.get("title"),
                        "merged": bool(meta.get("merged_at")),
                        "note": "Real Airflow PR snapshot for AIV live validation",
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            raw = subprocess.check_output(
                ["gh", "api", f"repos/apache/airflow/pulls/{n}/files", "--paginate"]
            )
            files = json.loads(raw.decode("utf-8", errors="replace"))
            if not isinstance(files, list):
                files = [files]
            written = 0
            for f in files:
                if f.get("status") == "removed" or not f.get("filename"):
                    continue
                text = content_from_patch(f.get("patch"))
                if text is None:
                    continue
                dest = tmp / "providers" / "snapshot" / f"pr-{n}" / f["filename"]
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(text, encoding="utf-8", errors="replace")
                written += 1
                if written >= 20:
                    break
            run(["git", "add", "-A"], cwd=tmp)
            run(["git", "commit", "-q", "-m", f"snapshot: apache/airflow#{n}"], cwd=tmp)
            run(["git", "push", "-u", "origin", branch, "--force"], cwd=tmp)
            existing = subprocess.run(
                ["gh", "pr", "list", "--repo", REPO, "--head", branch, "--json", "number", "-q", ".[0].number"],
                text=True,
                capture_output=True,
            ).stdout.strip()
            if not existing:
                run(
                    [
                        "gh",
                        "pr",
                        "create",
                        "--repo",
                        REPO,
                        "--base",
                        "main",
                        "--head",
                        branch,
                        "--title",
                        f"real: airflow#{n} — {meta.get('title','')[:60]}",
                        "--body",
                        f"Real apache/airflow#{n} snapshot for AIV validation.\nOrigin: https://github.com/apache/airflow/pull/{n}\n",
                    ],
                    cwd=tmp,
                )
        print(run(["gh", "pr", "list", "--repo", REPO, "--limit", "10"]))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
