# Module: `aiv-plugin-package-existence` (gate `package-existence`)

This gate checks newly declared dependencies against PyPI, npm, or Maven repositories. It turns a common hallucinated-package/slopsquatting risk into a concrete three-state result: confirmed, not found, or unverified.

## Architecture role

Default-on gate in the current source configuration. It reads changed manifests, extracts declarations, and delegates HTTP status checks to `HttpRegistryClient`. It is an external-input gate; `aiv verify` reports `package-registry` in `external_inputs`.

## Supported manifests

- Python requirements files.
- dependency sections in `pyproject.toml`.
- `dependencies` / `devDependencies` in `package.json`.
- Maven `<dependency>` declarations in `pom.xml`.

With a raw diff, only newly declared dependency lines/entries are eligible. File-only requests without raw diff evaluate the supplied manifest content.

## Runtime flow

1. Extract supported declarations and source lines.
2. Normalize ecosystem/name/version syntax.
3. Skip reviewed names in `allow`.
4. Cache duplicate lookups within the run.
5. Query configured registry/mirror with bounded timeout.
6. Classify response:
   - found: pass,
   - definitive not-found: `package-existence.unknown-package`,
   - timeout/transport/5xx: `package-existence.unverified`.
7. Block unknown packages; block unverified only in strict mode.

## Configuration

```yaml
gates:
  - id: package-existence
    enabled: true
    severity: fail
    config:
      strict: false
      timeout_seconds: 4
      allow: [company-internal-sdk]
      pypi_url: https://pypi.org/pypi/
      npm_url: https://registry.npmjs.org/
      maven_url: https://repo1.maven.org/maven2/
```

Point URLs at approved internal mirrors for governed or disconnected environments. `allow` skips a lookup and should remain base-ref protected.

## Findings

| Rule | Meaning | Default result |
|------|---------|----------------|
| `package-existence.unknown-package` | registry definitively reports absent | block |
| `package-existence.unverified` | lookup could not establish existence | visible pass unless `strict: true` |

Unverified never means confirmed. Reports include the package, ecosystem, manifest, and line.

## Example

Adding `ghost-tensor-utils==1.0` to `requirements.txt` and receiving a PyPI 404 produces a blocker. Running offline produces unverified evidence; strict mode converts it to a blocker.

## Security and reproducibility

Registry URLs come from trusted configuration. HTTP clients use bounded timeouts. Results depend on mutable external registries, so the profile is not `reproducible_local`; the verify response declares that dependency. For fully local deterministic AI-DLC blocking, disable this gate or use an immutable internal mirror/snapshot.

## Interactions

`dependency` verifies imports are declared locally; this gate verifies the declaration names exist. `external` can add lockfile/signature/SBOM scanners. `security` checks source secrets, not supply-chain reputation.

## Extension points

Add an ecosystem by extending declaration parsing plus the registry client contract. Preserve the three-state model and never convert transport errors into confirmed existence. Test scoped/new declarations and registry status classes.

## Tests and build

`PackageExistenceGateTest`, declaration parser tests, and `HttpRegistryClientTest` cover supported manifests, added-line scope, caching, allow lists, mirrors, timeouts, 404/2xx/5xx, strict mode, and structured findings.

```bash
mvn -pl aiv-plugin-package-existence test
mvn -pl aiv-plugin-package-existence verify
```

## Boundary

Existence is not safety. The gate does not prove package ownership, malware absence, version compatibility, license, or API behavior. Use provenance, vulnerability, and build tooling alongside it.
