# Why not just use PMD, Semgrep, or Checkstyle?

Those tools are excellent **static analyzers** for whole-codebase rules. AIV targets a different job: **PR-scoped integrity** on the diff - density, design rules, dependency surface on changed files, optional doc integrity - **without** uploading source to a third party and **without** requiring an LLM. You can run PMD and Semgrep **alongside** AIV; they answer different questions.

## Comparison (at a glance)

| | **AIV** | **PMD** | **Semgrep** | **Checkstyle** |
|--|---------|---------|---------------|----------------|
| **Primary unit** | PR diff (changed hunks / files) | AST / rules over configured scope | Pattern match over code | Style / layout conventions |
| **Default story** | “Is this change low-signal, off-design, or risky on imports?” | Bug / complexity / best-practice rules | Security & custom rules | Formatting and naming |
| **Config home** | `.aiv/config.yaml` + YAML design rules in-repo | `ruleset.xml` etc. | `.semgrep.yml` / packs | `checkstyle.xml` |
| **Air-gapped CI** | Single shaded JAR + local rules; no API | Yes, offline | Yes, offline with rules | Yes |
| **AI / slop signals** | First-class via design rules + density | Not the focus | Possible via custom rules | Not the focus |
| **Dependency confusion** | Import vs declared deps + known package roots (Maven/Python) | Not core | Custom rules | N/A |

## When PMD or Semgrep is the better hammer

- You need **repository-wide** bug patterns (e.g. empty catch, bad equals) with a mature rule catalog.
- You need **security taint** or **deep language-specific** rules that already exist in a Semgrep ruleset.
- You need **strict style** enforcement (Checkstyle) across the entire tree.

## When AIV is the better hammer

- You want **fast PR feedback** scoped to what changed, with **one** config and **one** report channel in CI.
- You want **design and “slop”** policies (emoji, markers, forbidden calls) **versioned next to the code**.
- You want **dependency surface** checks tied to **manifests** (`pom.xml`, `requirements.txt`) without standing up separate jobs for each ecosystem.

## Recommendation

Use **AIV** for PR integrity (density, design, dependency, docs). Add **PMD / Semgrep / Checkstyle** as separate jobs if you need whole-repo static analysis. The overlap is small; the combination is common in serious Java shops.
