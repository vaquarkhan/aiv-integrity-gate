# In-memory / agent check (`--diff-json`)

Agents and MCP-style tools can run the **same gates** on a proposed change set without a git commit.

## CLI

```bash
java -jar aiv-cli.jar --diff-json proposed.json --workspace .
```

Optional: `--fix` rewrites conflict markers / elision lines on disk, then evaluates cleaned contents.

## JSON shape

```json
{
  "baseRef": "memory",
  "headRef": "proposed",
  "rawDiff": "",
  "files": [
    {
      "path": "src/App.js",
      "changeType": "MODIFIED",
      "content": "const x = 1;\n"
    }
  ]
}
```

`changeType` is one of `ADDED`, `MODIFIED`, `DELETED` (unknown → `MODIFIED`). Escape newlines in `content` as `\\n`.

## MCP wiring

Expose a tool that writes this JSON and execs `aiv-cli` (or shells `scripts/aiv.sh --diff-json …`). There is no separate MCP server binary in this repo; the port is [`MemoryDiffProvider`](../aiv-adapter-git/src/main/java/io/github/vaquarkhan/aiv/adapter/git/MemoryDiffProvider.java).
