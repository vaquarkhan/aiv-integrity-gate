# Security plugin (`aiv-plugin-security`) — status

There is **no `aiv-plugin-security` Maven module in this repository yet**. It remains on the [product roadmap](../README.md#roadmap-short) as an optional future gate (e.g. secrets, CVE-style patterns, or rules that depend on external scanners), aligned with the discussion in project docs.

**What exists today:** integrity gates such as **dependency**, **density**, **design**, **doc-integrity**, and **invariant-template** — see the root [`pom.xml`](../pom.xml) `<modules>` list.

When a security-focused plugin is added, it will appear as `aiv-plugin-security/` and be listed in the reactor like the other plugins.

**Maintainer / developer of record (Maven POM):** Vaquar Khan — see `<developers>` in the root `pom.xml`.
