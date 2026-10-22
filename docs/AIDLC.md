# AIV as an AI-DLC verification sensor

AIV integrates with [AI-DLC](https://awslabs.github.io/aidlc-workflows/) as deterministic evidence at a lifecycle boundary. It does not replace AI-DLC orchestration. AI-DLC decides when a sensor runs and how humans respond; AIV computes the code/document integrity facts used by that decision.

![AIV inside AI-DLC](images/aiv-aidlc-flow.png)

**Figure 1: AI-DLC owns the workflow, AIV owns the evidence step.** Agent output becomes either a file-local request or a complete diff request. The sensor calls `aiv verify`, maps structured findings to the AI-DLC sensor schema, and returns advisory or fail-closed status. The verdict can also carry policy/evidence digests and an audit head.

## Why this integration adds value

AI-DLC includes deterministic sensor manifests around tools such as linters and type checkers. AIV adds a different evidence layer:

- strict parse/unverified states,
- conflict and agent edit artifacts,
- local import/manifest consistency,
- registry package existence classification,
- assertion-free added tests,
- added-line credential tripwires,
- repository design/rule policies,
- review-focus evidence such as cohesion/duplication,
- documentation/PR/header policy.

Each finding has a stable rule id, file, line, message, and blocking/advisory status. The same result can be published through JSON, SARIF, GitHub, and audit surfaces.

## Complete plugin tree

```text
integrations/aidlc/plugin/
  .aidlc-plugin/plugin.json
  sensors/
    aidlc-aiv.md
    aidlc-aiv-blocking.md
  tools/
    aiv-aidlc-sensor.py
```

The plugin uses AI-DLC's `{{HARNESS_DIR}}` token, so its packaged tool path remains stable across supported harnesses.

## Two sensor contracts

| Sensor | Command behavior | Best use |
|--------|------------------|----------|
| `aiv` | advisory, reports findings/integration problems without blocking | first adoption, post-save feedback, calibration |
| `aiv-blocking` | `--blocking`, trusted policy required, unavailable integration/external input fails closed | governed phase boundary |

Blocking mode requires `AIV_POLICY_REF` or `--policy-ref`. It forwards `--require-trusted-policy`, so missing/fallback/drifted policy cannot silently pass.

## Two evidence scopes

| Input | Evidence scope | Use |
|-------|----------------|-----|
| `--file path` | one file with empty raw diff; file-local checks | quick feedback |
| `--request change.json` | full changed-file set and raw diff | authoritative phase gate |

Full request mode is required when evidence depends on relationships between files or exact added lines, including production-plus-test context, manifest/declaration changes, duplication, or multi-area cohesion.

## Build prerequisite

The AI-DLC adapter calls `aiv verify` from release 2.0. Build the jar:

```bash
mvn -pl aiv-cli -am clean package
export AIV_JAR="$(pwd)/aiv-cli/target/aiv-cli-2.0.jar"
```

Confirm the jar reports the verify capabilities:

```bash
java -jar "$AIV_JAR" verify --capabilities
```

## Advisory file example

```bash
python integrations/aidlc/aiv_aidlc_sensor.py \
  --aiv-jar "$AIV_JAR" \
  --file src/main/java/app/Greeter.java
```

Response:

```json
{
  "pass": true,
  "violations": [
    {
      "file": "src/main/java/app/Greeter.java",
      "line": 1,
      "rule": "syntax.unverified",
      "message": "..."
    }
  ]
}
```

Advisory `pass: true` means the sensor did not block; violations still require visibility/review.

## Authoritative blocking example

```bash
export AIV_POLICY_REF=origin/main
python integrations/aidlc/aiv_aidlc_sensor.py \
  --aiv-jar "$AIV_JAR" \
  --request build/aiv-change.json \
  --workspace . \
  --blocking
```

Blocking mode fails when:

- an AIV hard gate fails,
- the jar/tool cannot run,
- stdout is not valid verdict JSON,
- trusted policy is absent/fallback/drifted,
- the profile declares `package-registry`, `external-tool`, or `decision-model` input.

For a blocking local deterministic sensor, use an explicit profile with package-existence, external, and Jev disabled. A separate advisory sensor can run those integrations if desired.

## Install into an AI-DLC source tree

Copy:

```text
integrations/aidlc/plugin -> <aidlc-workflows>/plugins/aiv-integrity
```

Then enable the plugin through that installation's plugin selection and run its normal packaging/compose process. The authored plugin contains its manifest, sensor definitions, and tool implementation; no core AI-DLC file needs to be edited by hand.

For a manual installed-workspace experiment, place the tool under the harness tool directory, place the sensor manifest under sensors, and add `aiv` or `aiv-blocking` to the target stage's `sensors` list.

## Runtime flow

1. AI-DLC emits a sensor call for a changed output.
2. Adapter reads file/request JSON.
3. Adapter launches `java -jar ... verify` with timeout.
4. Verify resolves/pins/freezes policy and runs AIV gates.
5. Adapter validates process status and JSON response.
6. AIV findings map to `{file, line, rule, message}` violations.
7. External-input declarations are surfaced as integration violations.
8. AI-DLC records sensor outcome and applies advisory/blocking workflow semantics.

## Security and trust

- Source/document content is untrusted input, never instructions to the wrapper.
- The wrapper uses subprocess argument arrays, not a shell string.
- Blocking calls require trusted policy and fail closed.
- `aiv verify` stdout is machine JSON; diagnostics stay on stderr.
- Policy comes from one pinned commit and immutable snapshot.
- Sensor timeout is shorter than the manifest timeout, leaving room for host cleanup.

## Validation

```bash
python -m unittest integrations.aidlc.test_aiv_aidlc_sensor -v
```

Tests cover advisory/blocked integration failures, required trusted policy, external-input rejection, file request construction, finding mapping, subprocess exit handling, and malformed/empty output.

## Related docs

- [HELLO-WORLD.md](HELLO-WORLD.md): run the integration after learning all gates.
- [MCP.md](MCP.md): verify request/response schema.
- [ARCHITECTURE.md](ARCHITECTURE.md): lifecycle and trust architecture.
- [MODULE-ADOPTION-GUIDE.md](MODULE-ADOPTION-GUIDE.md): choose an AI-DLC-safe profile.

**Author:** Viquar Khan
