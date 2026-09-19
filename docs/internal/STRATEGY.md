# Strategy (internal)

**What it is:** cheap, local check on the diff for objectively broken paste/parse/imports/secrets - leave it on forever.

## Done (makes it useful)

| Feature | Why |
|---------|-----|
| Pre-commit | Catch before PR |
| Added-lines-only hard rules | Safe on legacy files |
| Placeholder / tautology tests | Fake tests |
| Security gate (optional) | Credential paste on added lines |
| Baseline file | Adopt gradually without silencing rules |
| Advisory funnel | Soft signals do not sole-block |
| TP + high-breakage corpora | Prove catches where breakage exists |

## Still open

1. Richer secret heuristics (keep FP near zero)  
2. `// aiv-disable-next-line`  
3. Public seeded high-breakage GitHub demo repo  

See [CLAIMS.md](CLAIMS.md) and [../PRE-COMMIT.md](../PRE-COMMIT.md).
