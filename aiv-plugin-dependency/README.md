# Module: `aiv-plugin-dependency` (gate `dependency`)

The dependency gate checks that newly used Java and Python import roots are backed by the project dependency manifests or recognized as standard-library/first-party code.

## Architecture role

Default-on hard gate. It is a local manifest consistency check and makes no registry/network call. Registry existence is the separate `package-existence` gate.

## Runtime flow

1. Select changed Java/Python source files.
2. Extract import roots from source text.
3. Read Maven and Python dependency metadata from the source workspace.
4. Add Java standard roots and Python standard-library names.
5. Discover bounded first-party Python packages (`__init__.py`).
6. Apply configured approved roots.
7. Return unresolved-import findings.

## Inputs

Java imports are compared with known JDK roots, project packages, Maven group/artifact-derived roots, and configured entries. Python imports are compared with requirements/pyproject dependency names, standard library, discovered first-party packages, and configured entries.

## Configuration

```yaml
gates:
  - id: dependency
    enabled: true
    severity: fail
    config:
      allow: [company_internal, generated_api]
      file_extensions: [.java, .py]
```

Use `allow` for internal/generated roots that cannot be derived from a public manifest. Keep it narrow and under reviewed base-ref policy.

## Rule

`dependency.unresolved-import` identifies the source file/import root not backed by the available project evidence.

## Example

```python
import requests
import internal_sdk
```

`requests` passes when declared in requirements/pyproject. `internal_sdk` requires first-party discovery or explicit `allow`.

## Failure behavior

A confirmed unresolved import blocks. Repository layouts outside conventional discovery may need configuration. The check is prefix/root based, so it does not resolve every package manager edge case or compile code.

## Interactions

- `package-existence` asks whether a newly declared package exists remotely.
- `syntax` asks whether the source parses.
- `external` can run Maven/Gradle/pip/type-check tools for authoritative resolution.
- Frozen policy prevents a PR from adding itself to `allow` during the same strict run.

## Extension points

Add manifest readers/import grammars only with tests for aliases, comments, multiline forms, standard library, first-party layout, and ambiguous mapping. Keep registry/network work outside this module.

## Tests and build

`DependencyGateTest` covers Java/Python imports, manifests, allow entries, standard and first-party packages, extensions, failures, and ServiceLoader. `KnownJavaPackageRootsTest` covers the JDK root catalog.

```bash
mvn -pl aiv-plugin-dependency test
mvn -pl aiv-plugin-dependency verify
```

## Boundary

This gate provides deterministic local evidence that imports are declared. It does not prove dependency availability, version compatibility, API existence, or successful compilation.
