# Module: `aiv-adapter-git`

This module supplies AIV diffs from two sources: a real Git repository (`GitDiffProvider`) and strict in-memory JSON (`MemoryDiffProvider`) for agents, MCP tools, AI-DLC sensors, and tests.

## Architecture role

```text
Git refs OR JSON artifact -> DiffProvider -> Diff -> aiv-core
```

It depends only on `aiv-api` plus SLF4J. It does not evaluate gates or publish results.

## `GitDiffProvider` flow

1. Validate base/head refs against shell metacharacters and invalid path forms.
2. Run three-dot diff/name-status/numstat commands with prompts and pagers disabled.
3. Normalize added, modified, copied, and renamed paths; deleted files carry no content.
4. Read content from the workspace for checked-out HEAD or `git show` for another ref.
5. Capture raw unified diff, aggregate/per-file LOC, author email, good-signature status, and skip directive.
6. Return warnings for binary, oversized, or bounded-output cases.

Git subprocesses use a configurable timeout (default 120 seconds), temporary output files, a 64 MiB capture cap, and process-tree termination. Files above 2 MiB are skipped with a visible warning. Temporary files are cleaned with Windows-safe retries/fallback.

```java
Diff diff = new GitDiffProvider().getDiff(workspace, "origin/main", "HEAD");
```

### Git metadata

- Author email comes from the head commit and supports density/skip policy.
- `isHeadCommitSigned` is true only for Git status `G`.
- A standalone `aiv skip` or `/aiv skip` line in the latest commit is recorded for the core to decide.
- Per-file net LOC supports refactor and density behavior.

The provider never decides whether metadata is trusted. `aiv-core` applies allow lists and signature requirements.

## `MemoryDiffProvider`

The agent-facing provider parses strict RFC 8259 JSON through `JsonLite` and validates the schema. Unknown `changeType` values fail closed.

```json
{
  "baseRef": "memory",
  "headRef": "proposed",
  "rawDiff": "diff --git ...",
  "files": [
    {"path":"src/A.java","changeType":"MODIFIED","content":"class A {}"}
  ]
}
```

Required per file: nonblank string `path`. Optional: string `changeType` (`ADDED`, `MODIFIED`, `DELETED`) and string `content`. `files` must be an array of objects. The empty document defaults to an empty memory diff.

`rawDiff` matters: added-line gates use it for exact scope. A request with empty `rawDiff` intentionally falls back to whole-file evaluation, which is convenient for file-local checks but not authoritative for cross-file/added-line claims.

```java
MemoryDiffProvider provider = MemoryDiffProvider.fromJsonFile(request);
Diff diff = provider.getDiff(workspace, "ignored", "ignored");
```

## Configuration

| Control | Default | Purpose |
|---------|---------|---------|
| `-Daiv.git.timeout.seconds=N` | 120 | Git subprocess timeout |
| `-Daiv.git.executable=PATH` | `git` | alternate Git executable (mainly tests/portable installs) |

## Failure behavior

Invalid refs or Git failures throw `IllegalStateException` and map to CLI exit 3. Invalid JSON/schema throws `IllegalArgumentException` and maps to exit 2. Tool timeout kills descendants and fails the operation; it is never treated as a clean diff.

## Security properties

- `ProcessBuilder` receives refs as arguments, not a shell command.
- Git prompting is disabled.
- Output and file-size caps bound memory/disk use.
- Strict JSON rejects nested/path confusion and malformed numbers/strings.
- The provider supplies facts; trusted policy is established by `aiv-cli`.

## Extension points

Implement `DiffProvider` for another change source (IDE buffer, patch service, review API). Preserve repository-relative paths, full content, raw diff where available, warnings, and metadata. Do not silently discard unavailable evidence.

## Tests and build

`GitDiffProviderTest` covers real repositories, renames/copies, refs, metadata, skip text, caps, warnings, and path safety. Timeout/cleanup tests prove process-tree termination and stable cleanup. `MemoryDiffProviderTest` covers strict schema, Unicode, nested data, defaults, and invalid types.

```bash
mvn -pl aiv-adapter-git test
mvn -pl aiv-adapter-git verify
```

The timing-sensitive timeout tests use deliberately long processes; do not run Maven reactors concurrently in the same workspace.
