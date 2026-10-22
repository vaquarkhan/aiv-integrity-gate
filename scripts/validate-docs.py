#!/usr/bin/env python3
# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Validate local Markdown links, module README depth, and documentation hygiene."""
from pathlib import Path
from urllib.parse import unquote
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []
link_re = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)|!\[[^\]]*\]\(([^)]+)\)")

for doc in sorted(ROOT.rglob("*.md")):
    if any(part in {"target", ".git", "build"} for part in doc.parts):
        continue
    text = doc.read_text(encoding="utf-8")
    link_text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    if "\u2014" in text or "\u2013" in text:
        errors.append(f"{doc.relative_to(ROOT)}: contains an em/en dash")
    for match in link_re.finditer(link_text):
        raw = (match.group(1) or match.group(2) or "").strip()
        raw = raw.split()[0].strip("<>")
        if not raw or raw.startswith(("http://", "https://", "mailto:", "#", "kiro-artifact:")):
            continue
        target = unquote(raw.split("#", 1)[0])
        if not target:
            continue
        resolved = (doc.parent / target).resolve()
        if not resolved.exists():
            line = text.count("\n", 0, match.start()) + 1
            errors.append(f"{doc.relative_to(ROOT)}:{line}: missing local target {target}")

modules = sorted(p for p in ROOT.iterdir() if p.is_dir() and p.name.startswith("aiv-"))
for module in modules:
    readme = module / "README.md"
    if not readme.exists():
        errors.append(f"{module.name}: missing README.md")
        continue
    text = readme.read_text(encoding="utf-8")
    lines = text.splitlines()
    headings = sum(1 for line in lines if line.startswith("## "))
    if len(lines) < 50:
        errors.append(f"{module.name}/README.md: only {len(lines)} lines")
    if headings < 5:
        errors.append(f"{module.name}/README.md: only {headings} level-2 sections")
    for required in ("test", "build", "config"):
        if required not in text.lower():
            errors.append(f"{module.name}/README.md: missing {required} guidance")

for stale in ("aiv-demo-fail.svg", "aiv-demo-pass.svg", "aiv-shift-left.svg"):
    for doc in ROOT.rglob("*.md"):
        if stale in doc.read_text(encoding="utf-8"):
            errors.append(f"{doc.relative_to(ROOT)}: stale image reference {stale}")

if errors:
    print("Documentation validation failed:")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print(f"Documentation validation OK: {len(modules)} module READMEs and local links checked")
