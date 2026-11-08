#!/usr/bin/env python3
"""Run production-equivalent Git structure regressions against the shaded AIV CLI."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

GATES = [
    "syntax", "design", "cohesion", "density", "dependency", "doc-integrity",
    "rules", "package-existence", "test-adequacy", "security", "pr-metadata",
    "duplication", "external", "license-header", "jev",
]


ROOT = Path(__file__).resolve().parents[3]


def portable_path(path: Path) -> str:
    """Record a path relative to the repository so receipts never carry a local user or machine path."""
    try:
        return os.path.relpath(Path(path).resolve(), ROOT).replace("\\", "/")
    except ValueError:  # different drive on Windows
        return Path(path).name


def run(cwd: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, cwd=cwd, check=check, capture_output=True, text=True)


def git(cwd: Path, *args: str) -> str:
    return run(cwd, "git", *args).stdout.strip()


def config(allowed_gitlinks: list[str] | None = None) -> str:
    allowed = allowed_gitlinks or []
    lines = [
        "schema_version: 1",
        "fail_fast: false",
        "gates:",
        "  - id: invariant",
        "    enabled: true",
        "    severity: warn",
        "    config:",
        "      rule_actions:",
        "        invariant.merge-conflict: fail",
        "        invariant.unexpected-gitlink: fail",
        "        invariant.*: warn",
        "      unsuppressible_rules:",
        "        - invariant.merge-conflict",
        "        - invariant.unexpected-gitlink",
        "      allowed_gitlinks:",
    ]
    lines.extend([f'        - "{path}"' for path in allowed] or ["        []"])
    lines.extend([f"  - {{ id: {gate}, enabled: false }}" for gate in GATES])
    return "\n".join(lines) + "\n"


def init_repo(root: Path, allowed_gitlinks: list[str] | None = None) -> str:
    git(root, "init", "-q")
    policy = root / ".aiv"
    policy.mkdir()
    (policy / "config.yaml").write_text(config(allowed_gitlinks), encoding="utf-8")
    (root / "base.txt").write_text("base\n", encoding="utf-8")
    git(root, "add", ".aiv/config.yaml", "base.txt")
    run(root, "git", "-c", "user.name=AIV Benchmark", "-c", "user.email=benchmark@example.invalid",
        "commit", "-q", "-m", "base")
    return git(root, "rev-parse", "HEAD")


def commit(root: Path, message: str) -> None:
    git(root, "add", "-A")
    run(root, "git", "-c", "user.name=AIV Benchmark", "-c", "user.email=benchmark@example.invalid",
        "commit", "-q", "-m", message)


def findings(report: dict[str, Any]) -> list[str]:
    return [
        finding["rule_id"]
        for gate in report.get("gates", [])
        for finding in gate.get("findings", [])
    ]


def execute_case(jar: Path, case_id: str, expected_block: bool, expected_rule: str | None,
                 setup: Any, allowed: list[str] | None = None) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="aiv-structural-") as temp:
        root = Path(temp)
        base = init_repo(root, allowed)
        setup(root, base)
        out = root / "aiv-report.json"
        proc = run(root, "java", "-jar", str(jar), "--workspace", str(root),
                   "--diff", base, "--head", "HEAD", "--quiet", "--output-json", str(out), check=False)
        report = json.loads(out.read_text(encoding="utf-8")) if out.is_file() else {}
        rule_ids = findings(report)
        blocking = [gate for gate in report.get("gates", []) if gate.get("partition") == "blocking"]
        def canonical(gate: dict[str, Any]) -> bool:
            passed = gate.get("passed")
            blocks = gate.get("blocks_ci")
            expected = "pass" if passed is True and blocks is False else (
                "blocking" if passed is False and blocks is True else (
                    "advisory" if passed is False and blocks is False else None
                )
            )
            return expected is not None and gate.get("partition") == expected \
                and gate.get("result_key") == f"{gate.get('id')}:{expected}"
        report_valid = (
            report.get("schema_version") == 3
            and isinstance(report.get("passed"), bool)
            and isinstance(report.get("gates"), list)
            and all(canonical(gate) for gate in report.get("gates", []))
        )
        blocking_rule_ids = [
            finding.get("rule_id") for gate in blocking for finding in gate.get("findings", [])
        ]
        blocked = report_valid and proc.returncode == 1 and report.get("passed") is False and bool(blocking)
        clean_pass = report_valid and proc.returncode == 0 and report.get("passed") is True and not blocking
        passed = ((expected_block and blocked) or (not expected_block and clean_pass)) \
            and (expected_rule is None or expected_rule in blocking_rule_ids)
        return {
            "id": case_id,
            "expected_block": expected_block,
            "actual_block": blocked,
            "expected_rule": expected_rule,
            "rules": rule_ids,
            "exit_code": proc.returncode,
            "passed": passed,
            "stderr": proc.stderr[-1000:],
        }


def add_conflict(root: Path, _base: str) -> None:
    (root / "broken.py").write_text(
        "<<<<<<< HEAD\nleft = 1\n=======\nright = 2\n>>>>>>> branch\n", encoding="utf-8")
    commit(root, "add unresolved conflict")


def add_gitlink(root: Path, base: str) -> None:
    git(root, "update-index", "--add", "--cacheinfo", f"160000,{base},airflow")
    run(root, "git", "-c", "user.name=AIV Benchmark", "-c", "user.email=benchmark@example.invalid",
        "commit", "-q", "-m", "add gitlink")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("benchmarks/airflow/results/structural-latest.json"))
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        raise SystemExit(f"JAR not found: {jar}")

    cases = [
        execute_case(jar, "added-conflict", True, "invariant.merge-conflict", add_conflict),
        execute_case(jar, "unexpected-gitlink", True, "invariant.unexpected-gitlink", add_gitlink),
        execute_case(jar, "approved-gitlink", False, None, add_gitlink, ["airflow"]),
    ]
    positives = [case for case in cases if case["expected_block"]]
    negatives = [case for case in cases if not case["expected_block"]]
    true_positive = sum(case["passed"] for case in positives)
    true_negative = sum(case["passed"] for case in negatives)
    payload = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "scope": "synthetic production-equivalent Git structure regressions",
        "jar": portable_path(jar),
        "cases": cases,
        "metrics": {
            "positives": len(positives),
            "true_positives": true_positive,
            "objective_defect_recall": true_positive / len(positives) if positives else None,
            "negatives": len(negatives),
            "true_negatives": true_negative,
            "hard_false_positives": len(negatives) - true_negative,
        },
        "claim_limit": "Regression evidence only; not prevalence or broad AI-slop recall.",
    }
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["metrics"], indent=2))
    return 0 if all(case["passed"] for case in cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())
