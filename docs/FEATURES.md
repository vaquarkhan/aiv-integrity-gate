# AIV Features

Implemented features in the AIV project. All features run locally with no API keys or paid services unless noted.

**Author:** Vaquar Khan

---

## Quality Gates

| Gate | Description |
|------|-------------|
| **Density** | Logic density ratio (LDR) and Shannon entropy. Flags empty classes, boilerplate, and copy-paste. Java only for full LDR; other languages get entropy checks. |
| **Design** | YAML rules for forbidden and required patterns. Case-insensitive keyword matching. Supports multiple languages via configurable file extensions. |
| **Dependency** | Validates Java imports vs pom.xml and Python imports vs requirements.txt. Catches typos and supply-chain risks. |
| **Invariant** | Placeholder. Passes by default. Property-based test synthesis planned for future. |

---

## Configuration

| Feature | Description |
|---------|-------------|
| **Path exclusions** | `exclude_paths` in config with glob patterns (e.g. `**/generated/**`, `**/*.pb.java`). Skips files before gates run. |
| **Trusted authors** | Density gate bypass for listed email addresses. Reduces friction for core maintainers. |
| **Refactor exception** | Density skips when net lines removed exceeds threshold (default -50). Avoids flagging legitimate refactors. |
| **Config validation** | Malformed config throws instead of silently falling back. User sees clear error. |

---

## Human Override

| Feature | Description |
|---------|-------------|
| **Skip override** | Add `/aiv skip` or `aiv skip` to any commit message. All gates skip and report pass. |

---

## Output and Reporting

| Feature | Description |
|---------|-------------|
| **Structured logging** | SLF4J and Logback. No System.out. Report output via logging. |
| **GitHub PR comment** | Workflow posts pass/fail, density score, and violations as PR comment. Updates existing comment on new push. |

---

## Security

| Feature | Description |
|---------|-------------|
| **Ref validation** | Git refs validated before use. Rejects malicious input. |
| **Path traversal protection** | Resolved paths checked against workspace. No reading outside workspace. |
| **YAML safe deserialization** | SafeConstructor used for config and design rules. |
| **Process timeout** | Git commands timeout after 60 seconds. |
| **File size limit** | Files over 2MB skipped to avoid memory issues. |

---

## CI Integration

| Feature | Description |
|---------|-------------|
| **GitHub Actions** | Workflow in `.github/workflows/aiv.yml`. Runs on PR and push. |
| **Assignment Gate** | Separate workflow assigns PR author to linked issues when AIV passes. See [ASSIGNMENT-GATE.md](ASSIGNMENT-GATE.md). |

---

## Modules

| Module | Purpose |
|--------|---------|
| aiv-api | Interfaces, models, extension points |
| aiv-core | Orchestrator, gate loading |
| aiv-plugin-density | Logic density and entropy |
| aiv-plugin-design-lucene | Design compliance via YAML rules |
| aiv-plugin-dependency | Import validation |
| aiv-plugin-invariant-template | Invariant gate (placeholder) |
| aiv-adapter-git | Git diff provider |
| aiv-adapter-github | Report publisher (logging) |
| aiv-cli | Command-line entry point |

---

## Not Yet Implemented

See [FUTURE-ROADMAP.md](FUTURE-ROADMAP.md) for planned work: RAG-based design validation, property-based test synthesis, JUnit XML, JSON report, Docker image, published CLI, and more.
