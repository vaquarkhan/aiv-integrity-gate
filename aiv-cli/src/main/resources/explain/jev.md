# Gate: `jev` (advisory, opt-in, model-assisted)
## Why it exists
Some questions a reviewer asks are not decidable by a regex: "does this diff do more than the PR description says?", "do the added tests check anything about the behaviour they call?". This gate sends the diff and the PR text to a System One decision model (TypeSafe Jev, or a local Ollama decision model behind the same API) with the yes/no questions you configure, and writes the calibrated probability for each into the receipt. It is the only gate in AIV that calls a model.
## When a finding is wrong
A finding is a probability, not a verdict. `jev.<check-id>` with `P(yes)=0.91` means the model was 91 percent sure the answer to your question was yes. Raise `report_at`, rephrase the question with explicit `yes:` and `no:` criteria, or drop the check. The gate never blocks: whatever severity you configure, every outcome is pass or advisory, so a wrong answer costs a reviewer a glance and nothing else.
## How to fix
- Set the API key in the environment named by `api_key_env` (default `TYPESAFE_API_KEY`). Without it the gate reports `SKIPPED jev: ... not set` and runs nothing.
- For a fully local run point `base_url` at an Ollama server with a decision model pulled and set `api_key_env: ""`.
- Cost is the host's: TypeSafe lists $0.042 per million input tokens with output free (September 2026); `max_diff_chars` (default 60000) bounds what is sent. The receipt prints `input_tokens` for every run.
- Nothing from the repository other than the diff, the changed file list and the PR title/body is sent.
