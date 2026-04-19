# Helper scripts

Small utilities used by contributors and documentation—not installed as part of the Maven artifacts.

## validate-example

| Script | Platform |
|--------|----------|
| **`validate-example.sh`** | Linux, macOS, Git Bash |
| **`validate-example.bat`** | Windows `cmd` |

**Purpose:** From the **repository root**, run `mvn clean verify -pl aiv-cli -am` and execute the shaded CLI against the **parent workspace** with `--diff origin/main`, so the example project and root `.aiv` config are exercised together.

**Requirements:** JDK 17, Maven 3.9+, Git.

The JAR name includes the **current Maven project version** (for example `aiv-cli-1.0.2.jar`).

**Author:** Vaquar Khan
