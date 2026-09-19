# Module: `aiv-plugin-dependency`

**Role:** **Dependency gate** - compares **import** statements in changed source files against declared dependencies (for example **Maven `pom.xml`**, **Python requirements** / **pyproject.toml**). Uses curated mappings for common libraries where **Maven coordinates** do not match Java package prefixes 1:1, plus optional whitelisting.

Python **stdlib** modules and **first-party** packages (top-level `__init__.py` layouts) are allowed automatically.

## SPI

Registers as `QualityGate` with id **`dependency`**.

## Configuration

Gate `config` may include **`whitelist`** entries for intentional exceptions - see `DEVELOPER-CONFIGURATION.md`.

## See also

- [`DEVELOPER-CONFIGURATION.md`](../docs/DEVELOPER-CONFIGURATION.md)

**Author:** Vaquar Khan
