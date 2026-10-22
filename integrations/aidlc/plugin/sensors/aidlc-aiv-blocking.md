---
id: aiv-blocking
kind: deterministic
command: python3 {{HARNESS_DIR}}/tools/aiv-aidlc-sensor.py --file {{file_path}} --blocking
default_severity: blocking
description: Fail-closed AIV integrity gate for code outputs; requires AIV_POLICY_REF and a local deterministic profile
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

# AIV integrity sensor (blocking)

Fail-closed variant. It requires `AIV_POLICY_REF`, refuses workspace fallback,
and fails when AIV cannot run or when the profile declares package-registry,
external-tool, or decision-model inputs. Use a local deterministic AIV policy
for this sensor. Use full-request adapter mode for authoritative cross-file
phase evidence.
