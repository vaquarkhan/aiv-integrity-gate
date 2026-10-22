---
id: aiv
kind: deterministic
command: python3 {{HARNESS_DIR}}/tools/aiv-aidlc-sensor.py --file {{file_path}}
default_severity: advisory
description: Deterministic integrity evidence for AI-generated code through AIV Integrity Gate
category: code-quality
matches: "**/*.{java,py,js,ts,go,kt,kts,scala,rb,c,cc,cpp,h,hpp,sh,yaml,yml,json}"
input_schema:
  file_path: string
output_schema:
  pass: boolean
  violations:
    - file: string
      line: number
      rule: string
      message: string
timeout_seconds: 60
---

# AIV integrity sensor

Runs `aiv verify` over a Construction code output and returns
file-local findings in the standard AI-DLC sensor result shape. It also surfaces
any registry, external-tool, or decision-model dependency as
`aiv.integration.external-input`, so a blocking deterministic profile fails
closed instead of silently depending on mutable external state. The full-request
adapter mode is available for gates that need multi-file or raw-diff context.

Set `AIV_JAR` to the AIV CLI jar. Set `AIV_POLICY_REF` to the protected base ref.
The plugin is advisory by default; use the tool's `--blocking` mode for a
fail-closed integration where the host supports blocking sensor enforcement.
