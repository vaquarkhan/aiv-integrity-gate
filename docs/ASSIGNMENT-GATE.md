# Assignment Gate: Proof of Work over Permission to Work

The Assignment Gate addresses "Issue Squatting" (the Assignment Lock): when a contributor is assigned to an issue before submitting code, they can block capable engineers who see "Assigned" and move on. The assignee may ghost or submit low-quality work.

**Author:** Vaquar Khan

---

## The Problem

| Step | What Happens |
|------|--------------|
| 1 | Unknown contributor asks "Can I be assigned this?" |
| 2 | Maintainer assigns them |
| 3 | Capable engineers see "Assigned" and skip the issue |
| 4 | Assignee ghosts or submits low-quality code |
| 5 | Issue sits stagnant; maintainer wastes time |

---

## The Solution

**Proof of Work over Permission to Work:** Assign only after the contributor submits a PR that passes AIV.

| Step | What Happens |
|------|--------------|
| 1 | Contributor opens a PR with "Fixes #123" in the body |
| 2 | AIV runs on the PR |
| 3 | If AIV passes, Assignment Gate assigns the PR author to issue #123 |
| 4 | Assignment happens only after code quality is validated |

---

## How to Enable

Add the Assignment Gate workflow to your repo. It runs when the AIV Gate workflow completes successfully.

### Step 1: Ensure AIV Gate workflow exists

Your repo must have `.github/workflows/aiv.yml` (or equivalent) named **"AIV Gate"**.

### Step 2: Add the Assignment Gate workflow

Copy `.github/workflows/assignment-gate.yml` from this repo to your repo:

```bash
cp .github/workflows/assignment-gate.yml /path/to/your-repo/.github/workflows/
```

Or create `.github/workflows/assignment-gate.yml` with the content from this repo.

### Step 3: No extra configuration

The workflow uses `GITHUB_TOKEN`. No extra secrets required.

---

## How It Works

1. **Trigger:** Runs when the "AIV Gate" workflow completes successfully.
2. **Find PR:** Locates the open PR for the branch that triggered AIV.
3. **Extract issues:** Parses the PR body for "Fixes #123", "Closes #123", "Resolves #123".
4. **Assign:** Assigns the PR author to each linked issue.

---

## Policy Recommendation

Adopt a **No-Assignment-Until-PR** policy:

- Do not assign issues when someone asks "Can I work on this?"
- Reply: "Please open a PR first. We assign after AIV passes."
- Assignment Gate handles the rest when the PR passes.

---

## See Also

- [README.md](../README.md) — AIV overview
- [DEPLOYMENT.md](DEPLOYMENT.md) — Enabling AIV in your project
