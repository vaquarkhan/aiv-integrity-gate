#!/usr/bin/env python3
# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""AI-DLC sensor adapter for `aiv verify`.

Use --request for authoritative multi-file/diff evidence. --file is a convenient
single-file check for syntax, secrets, invariant markers, and other file-local rules.
Advisory mode reports integration failures without blocking. --blocking fails closed.
"""
import argparse
import json
import os
import subprocess
import sys


def build_file_request(file_path: str) -> str:
    with open(file_path, "r", encoding="utf-8") as fh:
        content = fh.read()
    return json.dumps({"artifact": {"baseRef": "memory", "headRef": "proposed",
                                     "rawDiff": "", "files": [
        {"path": file_path, "changeType": "MODIFIED", "content": content}
    ]}}, separators=(",", ":"))


def integration_result(message: str, blocking: bool) -> dict:
    return {"pass": not blocking, "violations": [{
        "file": "", "line": 0, "rule": "aiv.integration.unavailable", "message": message
    }]}


def run_verify(jar: str, request: str, workspace: str, policy_ref: str | None,
               blocking: bool) -> dict:
    cmd = ["java", "-jar", jar, "verify", "--workspace", workspace]
    if policy_ref:
        cmd += ["--policy-ref", policy_ref]
        if blocking:
            cmd.append("--require-trusted-policy")
    proc = subprocess.run(cmd, input=request, capture_output=True, text=True, timeout=55)
    if not proc.stdout.strip():
        raise RuntimeError(f"aiv verify produced no JSON (exit {proc.returncode}): {proc.stderr[-500:]}")
    verdict = json.loads(proc.stdout)
    if "error" in verdict or proc.returncode not in (0, 1):
        raise RuntimeError(str(verdict.get("error", f"aiv verify exited {proc.returncode}")))
    return verdict


def to_sensor_result(verdict: dict, blocking: bool) -> dict:
    violations = []
    for gate in verdict.get("gates", []):
        if gate.get("passed", True):
            continue
        findings = gate.get("findings", [])
        if not findings:
            findings = [{"rule_id": f"{gate.get('id', 'unknown')}.gate", "file": "",
                         "start_line": 0, "message": gate.get("message", "gate failed")}]
        for finding in findings:
            violations.append({
                "file": finding.get("file", ""),
                "line": finding.get("start_line", 0),
                "rule": finding.get("rule_id", gate.get("id", "")),
                "message": finding.get("message", gate.get("message", "")),
            })
    external_inputs = verdict.get("external_inputs", [])
    for external_input in external_inputs:
        violations.append({
            "file": "", "line": 0, "rule": "aiv.integration.external-input",
            "message": f"verification profile depends on {external_input}; disable it for a local deterministic AI-DLC sensor"
        })
    ok = not blocking or ((verdict.get("passed") is True and verdict.get("blocking") is False)
                          and not external_inputs)
    return {"pass": ok, "violations": violations}


def main() -> int:
    parser = argparse.ArgumentParser(description="AIV deterministic sensor for AI-DLC")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--file", help="single changed file (file-local evidence)")
    source.add_argument("--request", help="full aiv verify request JSON (authoritative mode)")
    parser.add_argument("--aiv-jar", default=os.environ.get("AIV_JAR"),
                        help="path to aiv-cli jar (or set AIV_JAR)")
    parser.add_argument("--workspace", default=".", help="workspace root")
    parser.add_argument("--policy-ref", default=os.environ.get("AIV_POLICY_REF"),
                        help="trusted git ref containing .aiv/config.yaml")
    parser.add_argument("--blocking", action="store_true",
                        help="fail closed on hard findings and integration failures")
    args = parser.parse_args()

    if args.blocking and not args.policy_ref:
        result = integration_result("blocking mode requires --policy-ref or AIV_POLICY_REF", True)
        print(json.dumps(result))
        return 1
    if not args.aiv_jar:
        result = integration_result("AIV_JAR is not set; deterministic sensor did not run", args.blocking)
        print(json.dumps(result))
        return 0 if result["pass"] else 1

    try:
        request = (open(args.request, "r", encoding="utf-8").read()
                   if args.request else build_file_request(args.file))
        verdict = run_verify(args.aiv_jar, request, args.workspace, args.policy_ref, args.blocking)
        result = to_sensor_result(verdict, args.blocking)
    except Exception as exc:
        result = integration_result(f"AIV sensor could not run: {exc}", args.blocking)
    print(json.dumps(result))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
