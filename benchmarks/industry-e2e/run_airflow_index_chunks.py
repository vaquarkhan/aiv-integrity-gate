#!/usr/bin/env python3
"""Fetch Airflow index PR patches in 2k chunks and score them with local aiv-cli.

The 15,575-row corpus/airflow-all/prs.jsonl file is metadata only. This script
downloads each PR's files/patch from GitHub, stores them, and runs AIV.

Usage:
  python run_airflow_index_chunks.py --chunk 0          # first 2000
  python run_airflow_index_chunks.py --chunk 1          # next 2000
  python run_airflow_index_chunks.py --chunk 0 --limit 50  # smoke
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
INDEX = Path(r"C:\Users\Administrator\Downloads\aiv-airflow-bench\corpus\airflow-all\prs.jsonl")
PATCH_DIR = HERE / "corpus" / "airflow-index" / "prs"
OUT = HERE / "results-airflow-index"
ROWS = OUT / "rows.jsonl"
PROGRESS = OUT / "progress.json"
JAR = ROOT / "aiv-cli" / "target" / "aiv-cli-2.0.jar"
REPO = "apache/airflow"
CHUNK_SIZE = 2000

spec = importlib.util.spec_from_file_location("e2e", HERE / "run_industry_e2e.py")
e2e = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(e2e)


def api_get(token: str, url: str) -> tuple[object | None, str]:
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "aiv-airflow-index-chunks",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            remaining = resp.headers.get("X-RateLimit-Remaining")
            body = resp.read()
            payload = json.loads(body.decode("utf-8"))
            if remaining is not None and remaining.isdigit() and int(remaining) < 30:
                return payload, f"rate-low:{remaining}"
            return payload, ""
    except urllib.error.HTTPError as exc:
        if exc.code in (403, 429):
            return None, f"github api {exc.code}"
        if exc.code == 404:
            return None, "not-found"
        return None, f"http {exc.code}"
    except Exception as exc:  # noqa: BLE001
        return None, f"error:{type(exc).__name__}"


def fetch_pr_record(token: str, number: int, meta: dict) -> tuple[dict | None, str]:
    dest = PATCH_DIR / f"{number}.json"
    if dest.is_file():
        try:
            existing = json.loads(dest.read_text(encoding="utf-8"))
            if existing.get("files") and existing.get("base_sha"):
                return existing, ""
        except json.JSONDecodeError:
            pass

    pr_url = f"https://api.github.com/repos/{REPO}/pulls/{number}"
    pr, warn = api_get(token, pr_url)
    if pr is None:
        return None, warn or "pr fetch failed"
    if not isinstance(pr, dict):
        return None, "bad pr payload"

    files: list[dict] = []
    page = 1
    while page <= 30:
        files_url = f"https://api.github.com/repos/{REPO}/pulls/{number}/files?per_page=100&page={page}"
        chunk, warn2 = api_get(token, files_url)
        if chunk is None:
            return None, warn2 or "files fetch failed"
        if not isinstance(chunk, list):
            return None, "bad files payload"
        if not chunk:
            break
        for f in chunk:
            files.append(
                {
                    "filename": f.get("filename"),
                    "status": f.get("status"),
                    "additions": f.get("additions"),
                    "deletions": f.get("deletions"),
                    "patch": f.get("patch"),
                    "patch_omitted": "patch" not in f,
                    "patch_truncated": False,
                    "previous_filename": f.get("previous_filename"),
                }
            )
        if len(chunk) < 100:
            break
        page += 1
        if warn2.startswith("rate-low"):
            warn = warn2

    base = (pr.get("base") or {}).get("sha")
    head = (pr.get("head") or {}).get("sha") or meta.get("head_sha")
    record = {
        "number": number,
        "url": pr.get("html_url") or meta.get("url") or f"https://github.com/{REPO}/pull/{number}",
        "title": pr.get("title") or meta.get("title"),
        "state": (pr.get("state") or meta.get("state") or "").lower(),
        "merged": bool(pr.get("merged")),
        "draft": bool(pr.get("draft")),
        "user": ((pr.get("user") or {}).get("login")) or meta.get("user"),
        "created_at": pr.get("created_at") or meta.get("created_at"),
        "closed_at": pr.get("closed_at") or meta.get("closed_at"),
        "merged_at": pr.get("merged_at") or meta.get("merged_at"),
        "base_sha": base,
        "head_sha": head,
        "additions": pr.get("additions") if pr.get("additions") is not None else meta.get("additions"),
        "deletions": pr.get("deletions") if pr.get("deletions") is not None else meta.get("deletions"),
        "changed_files": pr.get("changed_files") if pr.get("changed_files") is not None else meta.get("changed_files"),
        "body": pr.get("body") or meta.get("body"),
        "too_big": False,
        "files": files,
        "label": meta.get("label") or "airflow_index",
        "expect_hard_fail": bool(meta.get("expect_hard_fail")),
    }
    if any(f.get("patch_omitted") for f in files):
        record["too_big"] = True
    PATCH_DIR.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(record), encoding="utf-8")
    return record, warn


def load_index() -> list[dict]:
    rows = []
    with INDEX.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                rows.append(json.loads(line))
    rows.sort(key=lambda r: int(r["number"]))
    return rows


def already_scored() -> set[int]:
    done: set[int] = set()
    # prior full stored-patch run
    prior = HERE / "results-full" / "rows.jsonl"
    for path in (prior, ROWS):
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("project_repo") == REPO and row.get("pr") is not None:
                done.add(int(row["pr"]))
            # results-full ids like ai-year-2532 / e2e-proof-59957
            if str(row.get("id", "")).startswith(("ai-year-", "e2e-proof-", "airflow-", "airflow-index-")):
                try:
                    done.add(int(row["pr"]))
                except (TypeError, ValueError, KeyError):
                    pass
    return done


def write_progress(chunk: int, stats: dict) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    payload = {
        "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "chunk": chunk,
        "chunk_size": CHUNK_SIZE,
        **stats,
    }
    PROGRESS.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def summarize_rows() -> dict:
    counts: Counter[str] = Counter()
    n = 0
    if ROWS.is_file():
        for line in ROWS.read_text(encoding="utf-8").splitlines():
            if line.strip():
                counts[json.loads(line).get("verdict", "?")] += 1
                n += 1
    return {"n_rows": n, "by_verdict": dict(counts)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chunk", type=int, required=True, help="0-based chunk index (size 2000)")
    parser.add_argument("--limit", type=int, default=0, help="optional cap inside the chunk for smoke")
    parser.add_argument("--chunk-size", type=int, default=CHUNK_SIZE)
    args = parser.parse_args()
    chunk_size = args.chunk_size

    if not JAR.is_file():
        print(f"missing jar: {JAR}", flush=True)
        return 2
    if not INDEX.is_file():
        print(f"missing index: {INDEX}", flush=True)
        return 2

    token = subprocess.check_output(["gh", "auth", "token"], text=True).strip()
    fetch = e2e.Fetcher(token)
    index = load_index()
    start = args.chunk * chunk_size
    end = min(len(index), start + chunk_size)
    if start >= len(index):
        print(f"chunk {args.chunk} out of range; index has {len(index)} rows", flush=True)
        return 0
    slice_rows = index[start:end]
    if args.limit > 0:
        slice_rows = slice_rows[: args.limit]

    scored = already_scored()
    OUT.mkdir(parents=True, exist_ok=True)
    PATCH_DIR.mkdir(parents=True, exist_ok=True)

    print(
        f"chunk={args.chunk} range=[{start},{end}) selected={len(slice_rows)} "
        f"index={len(index)} already_scored={len(scored)}",
        flush=True,
    )

    blocked = passed = unscored = errors = skipped = 0
    for i, meta in enumerate(slice_rows, 1):
        number = int(meta["number"])
        if number in scored:
            skipped += 1
            continue

        record, warn = fetch_pr_record(token, number, meta)
        if warn.startswith("github api") or warn.startswith("rate-low"):
            print(f"stop at PR {number}: {warn}", flush=True)
            write_progress(args.chunk, {
                "stopped": warn,
                "last_pr": number,
                "blocked": blocked,
                "passed": passed,
                "unscored": unscored,
                "errors": errors,
                "skipped": skipped,
                **summarize_rows(),
            })
            return 3
        if record is None:
            row = {
                "id": f"airflow-index-{number}",
                "project_repo": REPO,
                "pr": number,
                "url": meta.get("url"),
                "title": meta.get("title"),
                "label": meta.get("label") or "airflow_index",
                "expect_hard_fail": bool(meta.get("expect_hard_fail")),
                "merged": meta.get("merged"),
                "verdict": "UNSCORED",
                "exit": None,
                "reason": warn or "fetch failed",
                "bench": "airflow-index",
                "chunk": args.chunk,
            }
            with ROWS.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(row) + "\n")
            unscored += 1
            scored.add(number)
            continue

        case = {
            "id": f"airflow-index-{number}",
            "pr": number,
            "url": record.get("url"),
            "title": record.get("title"),
            "label": meta.get("label") or "airflow_index",
            "kind": "pull_request",
            "merged": record.get("merged"),
            "expect_hard_fail": bool(meta.get("expect_hard_fail")),
        }
        row = e2e.score_case(REPO, case, PATCH_DIR, fetch, JAR)
        row["id"] = case["id"]
        row["bench"] = "airflow-index"
        row["chunk"] = args.chunk
        with ROWS.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row) + "\n")
        scored.add(number)

        v = row.get("verdict")
        if v == "BLOCK":
            blocked += 1
        elif v == "PASS":
            passed += 1
        elif v == "ERROR":
            errors += 1
        else:
            unscored += 1

        if i % 25 == 0 or v == "BLOCK" or warn:
            print(
                f"[chunk {args.chunk} {i}/{len(slice_rows)}] #{number}: {v} "
                f"{row.get('reason','')[:120]} {('('+warn+')') if warn else ''}",
                flush=True,
            )
        if fetch.stopped:
            print("fetcher stopped:", fetch.stopped, flush=True)
            write_progress(args.chunk, {
                "stopped": fetch.stopped,
                "last_pr": number,
                "blocked": blocked,
                "passed": passed,
                "unscored": unscored,
                "errors": errors,
                "skipped": skipped,
                **summarize_rows(),
            })
            return 3

    stats = {
        "stopped": "",
        "blocked": blocked,
        "passed": passed,
        "unscored": unscored,
        "errors": errors,
        "skipped": skipped,
        **summarize_rows(),
    }
    write_progress(args.chunk, stats)
    print("chunk finished", stats, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
