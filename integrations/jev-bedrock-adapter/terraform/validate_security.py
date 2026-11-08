"""Static safety checks for the JEV Terraform tree. No AWS access required."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent
TEXT_SUFFIXES = {".tf", ".hcl", ".md", ".yml", ".yaml", ".example"}
SKIP_PARTS = {".terraform"}

checks = {
    "12-digit account id": re.compile(r"(?<![0-9])[0-9]{12}(?![0-9])"),
    "email address": re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    "AWS access key id": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "hardcoded AWS credential": re.compile(
        r"(?im)^\s*(?:access_key|secret_key|session_token|profile)\s*=\s*\"[^\"<][^\"]*\""
    ),
}

failures = []
files = []
for path in ROOT.rglob("*"):
    if not path.is_file() or any(part in SKIP_PARTS for part in path.parts):
        continue
    if path.name.endswith(".tfvars.example") or path.name.endswith(".hcl.example") or path.suffix in TEXT_SUFFIXES:
        files.append(path)
        text = path.read_text(encoding="utf-8", errors="replace")
        for name, pattern in checks.items():
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                failures.append(f"{path.relative_to(ROOT)}:{line}: {name}")

if failures:
    print("Terraform safety validation failed:")
    print("\n".join(f"  {item}" for item in failures))
    sys.exit(1)

print(f"Terraform safety validation OK: {len(files)} files scanned")
