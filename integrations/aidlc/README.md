# AIV sensor for AI-DLC

Run **AIV Integrity Gate** at an [AI-DLC](https://awslabs.github.io/aidlc-workflows/)
verification boundary. The adapter calls `aiv verify` and returns the standard sensor
shape: `pass` plus file/line/rule/message violations.

> **About AI-DLC.** AI-DLC is an open-source project published by AWS at
> [github.com/awslabs/aidlc-workflows](https://github.com/awslabs/aidlc-workflows) under the
> MIT-0 license. AIV and this plugin are a separate, independent Apache-2.0 project. They are
> not part of AI-DLC and are not affiliated with or endorsed by AWS.
>
> **What it helps.** AI-DLC's built-in sensors check documents, traceability, lint and types.
> This plugin adds deterministic code-diff evidence (conflict blocks, agent edit artifacts,
> unresolved imports, assertion-free tests, added secrets) at the Construction approval gate,
> using the same `.aiv` policy that later runs in the pull request. See
> [docs/AIDLC.md](../../docs/AIDLC.md#what-using-aiv-with-ai-dlc-helps).

## Two evidence modes

| Mode | Input | Best for |
|------|-------|----------|
| **Full request** (authoritative) | `--request change.json` with all changed files and `rawDiff` | package, test, duplication, added-line, and cross-file evidence |
| **Single file** (convenience) | `--file src/App.java` | syntax, invariant markers, secrets, and other file-local checks |

Use full-request mode at a phase gate. Single-file mode is suitable for fast post-save feedback but does not claim cross-file completeness.

## Complete plugin

`plugin/` is a ready AI-DLC plugin source tree:

```text
plugin/
  .aidlc-plugin/plugin.json
  sensors/aidlc-aiv.md
  tools/aiv-aidlc-sensor.py
```

The plugin contains two sensor manifests: `aiv` (advisory) and `aiv-blocking`
(fail closed). The blocking command includes `--blocking`, requires
`AIV_POLICY_REF`, refuses policy-tree drift, and rejects profiles that declare
package-registry, external-tool, or decision-model inputs.

Copy it to `plugins/aiv-integrity/` in an AI-DLC source tree, enable the plugin through
the install's plugin selection, and run the normal AI-DLC packager. The sensor command
uses `{{HARNESS_DIR}}/tools/aiv-aidlc-sensor.py`, so the packaged path is stable across
supported harnesses.

For a manual install, copy the sensor manifest to the install's sensor directory and
the tool to `{{HARNESS_DIR}}/tools/aiv-aidlc-sensor.py`; opt a stage in with
`sensors: [aiv]`.

## Configure

```bash
export AIV_JAR=/path/to/aiv-cli.jar
export AIV_POLICY_REF=origin/main
```

Advisory single-file check:

```bash
python3 integrations/aidlc/aiv_aidlc_sensor.py --file src/App.java
```

Authoritative blocking phase check:

```bash
python3 integrations/aidlc/aiv_aidlc_sensor.py \
  --request build/aiv-change.json \
  --workspace . \
  --policy-ref origin/main \
  --blocking
```

In advisory mode, an unavailable jar or tool failure is returned as a visible
`aiv.integration.unavailable` violation without stopping the workflow. In blocking mode,
the same condition fails closed with exit 1. Blocking mode also adds
`--require-trusted-policy`, so a missing base-ref policy cannot silently fall back to the
workspace.

## Prerequisites

- The source-built AIV CLI jar containing `aiv verify`.
- JDK 17 or newer and Python 3.

Design and workflow: [docs/AIDLC.md](../../docs/AIDLC.md). Request schema:
[docs/MCP.md](../../docs/MCP.md).
