# Copilot instructions — anti-slop generation rules

These rules reduce AI slop *at the point of generation*, before it ever becomes a
commit or PR. They complement (they do not replace) the AIV Gate, which is the
deterministic hard gate that runs first in CI. See `docs/pipeline-aiv-copilot.md`.

When generating or editing code in this repository, you MUST NOT produce any of the
following. Each is an objective "AI artifact" that the AIV Gate blocks on, so emitting
it only causes a failed check:

1. Elision / truncation markers. Never replace real code with a summary such as
   `// ... rest of the code unchanged`, `# ... existing code ...`, `/* remaining
   implementation */`, or `<!-- truncated -->`. Always emit the complete file or region.
2. Hallucinated imports or dependencies. Only import packages/modules/symbols that
   actually exist in this project or its declared dependencies.
3. Stub bodies presented as an implementation (`raise NotImplementedError`,
   `throw new UnsupportedOperationException`, `TODO: implement`) in the same change that
   claims to implement the feature.
4. Merge-conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`).
5. Prompt / assistant leakage (`As an AI language model`, `Here is the updated code:`).

Advisory (a review may flag, not hard-block): keep changes scoped, match existing style,
ensure everything parses/compiles, add tests for new behavior.
