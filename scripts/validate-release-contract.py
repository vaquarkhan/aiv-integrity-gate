"""Check local release metadata consistency without publishing anything."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
action = (ROOT / "action.yml").read_text(encoding="utf-8")
errors = []

if "<groupId>io.github.vaquarkhan</groupId>" not in pom:
    errors.append("pom.xml no longer declares the expected Maven group")
if 'URL="$IN_CENTRAL/io/github/vaquarkhan/aiv-cli/$IN_VERSION/aiv-cli-$IN_VERSION.jar"' not in action:
    errors.append("action.yml Maven Central URL does not match io.github.vaquarkhan:aiv-cli")
if 'verify --capabilities' not in action or '"trusted-policy"' not in action:
    errors.append("action.yml no longer requires the hardened capability handshake")
deployment = (ROOT / "docs" / "DEPLOYMENT.md").read_text(encoding="utf-8")
if "uses: vaquarkhan/aiv-integrity-gate@main" in deployment:
    errors.append("deployment guide still recommends a mutable Action branch")
if not (ROOT / "docs" / "GITHUB-QUICKSTART.md").is_file():
    errors.append("five-minute GitHub quickstart is missing")
if not (ROOT / "docs" / "RELEASE-CANDIDATE.md").is_file():
    errors.append("release candidate handoff is missing")

if errors:
    print("Release contract validation failed:")
    print("\n".join(f"  {item}" for item in errors))
    sys.exit(1)

version = re.search(r"<version>([^<]+)</version>", pom).group(1)
print(f"Release contract validation OK: source version {version}; no publish attempted")
