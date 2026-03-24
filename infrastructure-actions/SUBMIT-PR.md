# Submitting AIV to apache/infrastructure-actions

This directory contains the AIV connector for Apache infrastructure-actions. To submit:

## 1. Fork and clone

```bash
git clone https://github.com/apache/infrastructure-actions.git
cd infrastructure-actions
```

## 2. Copy files

Copy the contents of this directory into the infrastructure-actions repo:

- `aiv/` → `infrastructure-actions/aiv/`
- `.github/workflows/aiv.yml` → `infrastructure-actions/.github/workflows/aiv.yml`

## 3. Prerequisites

Before the action works in production:

- Publish `aiv-cli` to Maven Central as `org.apache.aiv:aiv-cli`
- Or publish a GitHub release with `aiv-cli-1.0.0.jar` in `apache/aiv-gate`

## 4. Create PR

1. Create a branch
2. Add the files
3. Open a PR with description:

```
Add AIV (Automated Integrity Validation) action

AIV catches logic density issues, design violations, and low-quality code
in pull requests. Projects opt-in with 3 lines in .github/workflows.

- No secrets required (default mode)
- Works with fork PRs
- Author: Vaquar Khan
```

## 5. actions.yml

No entry needed for `apache/infrastructure-actions/*` - it's in the allowed namespace. The action uses `actions/checkout@34e114876b0b11c390a56381ad16ebd13914f8d5` and `actions/setup-java@c1e323688fd81a25caa38c78aa6df2d33d3e20d9` which are already allowed.
