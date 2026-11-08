"""Verify that source and packaged AIV presets explicitly name all 18 gates."""
from pathlib import Path
import sys

import yaml

ROOT = Path(__file__).resolve().parent.parent
NAMES = ("minimal", "java-ci", "agent-paste-strict", "asf", "airflow")
EXPECTED = {
    "syntax", "invariant", "dependency", "package-existence", "test-adequacy", "security",
    "design", "density", "cohesion", "duplication", "doc-integrity", "pr-metadata",
    "license-header", "external", "rules", "jev", "review-routing", "change-policy",
}
errors = []

for name in NAMES:
    paths = (
        ROOT / "presets" / name / "config.yaml",
        ROOT / "aiv-cli" / "src" / "main" / "resources" / "presets" / name / "config.yaml",
    )
    seen = []
    for path in paths:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
        gates = document.get("gates", []) if isinstance(document, dict) else []
        ids = [gate.get("id") for gate in gates if isinstance(gate, dict)]
        if len(ids) != len(set(ids)):
            errors.append(f"{path.relative_to(ROOT)}: duplicate gate ids")
        if set(ids) != EXPECTED:
            errors.append(f"{path.relative_to(ROOT)}: expected exactly the 18 gate ids")
        seen.append(ids)
    if seen[0] != seen[1]:
        errors.append(f"preset {name}: source and packaged gate order differs")

if errors:
    print("Preset validation failed:")
    print("\n".join(f"  {error}" for error in errors))
    sys.exit(1)

print(f"Preset validation OK: {len(NAMES)} source/resource pairs, {len(EXPECTED)} gates each")
