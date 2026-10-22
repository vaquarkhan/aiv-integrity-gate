# AIV LinkedIn carousel story

Format: 4:5 portrait PDF document, one idea per page, readable without a presenter.

1. **Hook:** AI agents create change faster than teams can verify it.
2. **Problem:** Every pull request is a request to spend human attention; unverified change creates a review tax.
3. **Why existing tools leave a gap:** compilers, SAST, LLM reviewers, orchestrators, and policy engines each solve a different layer; no shared protected evidence receipt.
4. **AIV solution:** deterministic, diff-scoped evidence with a frozen policy and structured verdict.
5. **How it works:** capture change, pin policy commit, freeze `.aiv`, run gates, publish/anchor evidence.
6. **Gate model:** four evidence families, 16 gate ids, hard facts separated from advisory signals.
7. **Change integrity gates:** syntax, invariant, dependency, package-existence.
8. **Behavioral evidence gates:** test-adequacy, security, design, rules.
9. **Review-focus gates:** density, cohesion, duplication, external SARIF.
10. **Governance gates:** doc-integrity, PR metadata, license-header, advisory Jev.
11. **Trust boundary:** the proposed change cannot replace its own yardstick; one pinned commit and immutable snapshot.
12. **Outputs and integrations:** local, CI/Action, JSON/SARIF/GitHub, `aiv verify`, policy engines, audit/report.
13. **AI-DLC:** AI-DLC owns orchestration; AIV supplies advisory or fail-closed phase evidence.
14. **Proof:** 25 projects, 844 tests, zero failures/errors, coverage gates, Critical 0 / High 0, docs/integration/visual validation.
15. **Adoption:** observe, calibrate, protect, enforce; start minimal and add only relevant gates.
16. **CTA:** pilot one repository; reduce time spent proving basic facts.

Copy rules:

- No claim that AIV stops all AI slop or identifies authorship.
- No unmeasured productivity or precision percentage.
- Minimum 13-point supporting text; 26-point or larger primary copy.
- No more than four cards or five bullets on one page.
- Every acronym is expanded or explained on first use.
- Each page includes product name, page number, and repository URL.

**Author:** Viquar Khan
