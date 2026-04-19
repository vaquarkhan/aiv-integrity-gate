# Maven version and Maven Central (CLI)

## Single source of truth

The release **version** is the `<version>` element in the reactor root [`pom.xml`](../pom.xml) (parent artifact `aiv-gate`, currently under the `<project>` element).

Every consumer-facing example that pins a numeric release (download URL, composite action `aiv-version`, `scripts/install-aiv.sh`, CI snippets) should use **that same string**.

## Shaded CLI on Maven Central

Download URL pattern (HTTPS, no trailing slash on the version segment):

```text
https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/<version>/aiv-cli-<version>.jar
```

**Maven coordinates** (for tooling and documentation):

```text
io.github.vaquarkhan.aiv:aiv-cli:<version>
```

Replace `<version>` with the reactor root `<version>` (for example `1.0.3`).

## Resolve `<version>` from a Git clone

Using Maven (from the repository root):

```bash
mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f pom.xml
```

Or use [`scripts/print-maven-version.sh`](../scripts/print-maven-version.sh), which runs the same command against the root POM next to the script.

Example: download the CLI JAR using the resolved version on Linux or macOS:

```bash
VERSION="$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version -f pom.xml)"
curl -fsSL -o aiv-cli.jar \
  "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/${VERSION}/aiv-cli-${VERSION}.jar"
```

## Composite GitHub Action

[`action.yml`](../action.yml) input **`aiv-version`** defaults to the current release line. When you publish a new Maven release, update that default (and any hardcoded URLs in your own workflows) so it matches the root POM `<version>`.

## Related

- [DEPLOYMENT.md](DEPLOYMENT.md) - Central publishing and CI
- [TUTORIAL.md](TUTORIAL.md) - End-to-end usage
