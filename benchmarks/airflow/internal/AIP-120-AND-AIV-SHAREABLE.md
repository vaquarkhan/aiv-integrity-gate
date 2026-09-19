# AIV Gate and AIP-120 - evidence brief (INTERNAL)

**Status:** maintainer notes only. Not a public product page.

**Audience:** Airflow / ASF discussants evaluating AIP-120 and related “AI contribution volume” concerns  
**Prepared:** 2026-09-19  
**Code + data:** [`vaquarkhan/aiv-integrity-gate`](https://github.com/vaquarkhan/aiv-integrity-gate) @ `main` (`c44326d` and later)  
**Primary artifacts:** [`reports/latest.html`](reports/latest.html), [`reports/latest.json`](reports/latest.json), [`corpus/cases.json`](corpus/cases.json), [`METHODOLOGY.md`](METHODOLOGY.md)

## How to read this document

| Tag | Meaning |
|-----|---------|
| **VERIFIED** | Reproduced from public GitHub API and/or checked-in benchmark outputs in this repository |
| **NOT CLAIMED** | Explicitly unsupported; do not quote in wider discussion |
| **OPINION** | Product/policy judgment, not a measured percentage |

No CI-savings % appears as a **measured bill**. Extrapolations to AIP-120’s 508 use the **ESTIMATED** tag only (see census report).

---

## 1. Direct answer to “can the benchmark show a good dataset, no FPs, and % blocked?”

**Partly yes — with strict definitions.**

| Question | What the benchmark **can** show (**VERIFIED**) | What it **cannot** show (**NOT CLAIMED**) |
|----------|-----------------------------------------------|-------------------------------------------|
| Good labeled dataset? | Yes: schema v2 corpus with explicit label classes + PR URLs ([`corpus/cases.json`](corpus/cases.json)) | That closed-unmerged PRs are “AI slop” |
| No false positives? | **0 / 12** hard fails on **merged controls** + **1 / 1** clean synthetic negative (**VERIFIED** in `latest.json`) | “Never FP in production Airflow” or “0 FP on all closed-unmerged” |
| What % blocked? | On **this 51-case run**: hard-fail rates by label class (below) | % of AIP-120’s **508** external closed-unmerged, or % of CI minutes saved |

**Hard-gate results from published run** (`generated_at` **2026-09-19T17:04:26Z**, `n_cases` **51**, `n_errors` **0**) — **VERIFIED** from [`reports/latest.json`](reports/latest.json):

| Cohort | n | Hard-fail | Interpretation |
|--------|---|-----------|----------------|
| Labeled positives (3 synthetic objective markers) | 3 | **3 TP, 0 FN** → recall **100%** | Tool catches what it is designed to catch when markers are present |
| Merged controls + clean synthetic | 13 | **0 FP, 13 TN** → FP rate **0%** | On this control set, hard gate did not block merge-quality samples |
| Closed-unmerged **without** objective markers | 35 | **1** hard-fail (**2.9%** flag rate) | Population flag-rate only — **not** “AI slop catch rate” |

The single closed-unmerged hard-fail was **[apache/airflow#73124](https://github.com/apache/airflow/pull/73124)** (`invariant.placeholder` / `FIXME` in a workflow file) — **VERIFIED** in `latest.json`. That is a known class of false positive for “AI slop” framing until **added-lines-only** scoping lands (**OPINION** on remediation; FP event itself is **VERIFIED**).

**Discovery scan in the same AIP-120 window:** among **35** recently closed-unmerged PRs scanned for objective patch markers (conflict / SEARCH-REPLACE / elision / attribution), **0** had those markers — **VERIFIED** (`corpus/cases.json` → `stats.discovery_objective_from_closed_scan: 0`).

So: the dataset is good for measuring **precision on objective defects** and **FP on merged controls**. It is **not** yet a labeled “AI authorship” dataset, and it does **not** justify a savings % against AIP-120’s 508.

---

## 2. What AIP-120 is (from the discuss thread / wiki) vs what AIV is

### 2.1 AIP-120 problem statement (public proposal — not re-derived here)

Jarek’s [DISCUSS] / [AIP-120](https://cwiki.apache.org/confluence/spaces/AIRFLOW/pages/451974711/AIP-120+Run+CI+for+external+contributions+in+contributor+forks) (window **2026-06-20 .. 2026-09-18**) reports roughly:

- ~**4,009** PRs opened (~45/day)
- ~**65%** external
- External closed-unmerged ~**19.5%** vs ~**5.2%** internal → ~**508** external closed-unmerged in 90 days
- Full matrix ~**4,392** job-minutes vs docs-scale ~**31**

Community pushback in-thread (Ash, Ferruzzi, and others) focuses on **contributor UX**, flaky `main`, wasted effort before acceptance, preference for **issue-gating**, and that LLM volume is real but fork-CI may be the wrong fix. Those are policy arguments; this brief does not adjudicate them.

### 2.2 Independent volume cross-check (**VERIFIED** 2026-09-19)

Same calendar window, unfiltered Search API (not AIP-120’s `author_association` filter):

| Query | Total |
|-------|-------|
| `repo:apache/airflow is:pr created:2026-06-20..2026-09-18` | **4044** |
| `… is:closed is:unmerged` (same window) | **597** |

Order of magnitude matches AIP-120. Exact equality is **not** expected (AIP-120 filters associations; this check does not).

### 2.3 AIV Gate role (**OPINION** grounded in design + measurements)

| Need raised in AIP-120 / thread | AIV role | Tag |
|---------------------------------|----------|-----|
| Stop unverified large changes consuming full matrix | Possible **cheap pre-filter** for **objective** defects only | Design intent; savings **NOT CLAIMED** |
| Detect “low value / doesn’t do what PR claims” | **Out of scope** for hard gate | **NOT CLAIMED** |
| LLM judge in block path | Explicitly excluded | Design rule |
| Reduce false maintainer attention | Only if blocks true defects and almost never blocks good PRs | Needs larger labeled TP set |

**AIV does not replace AIP-120.** Fork-CI vs issue-gating is a **policy** choice. AIV is a **deterministic integrity gate** (syntax / design markers / invariant conflict & AI-edit artifacts / optional density warn+label).

---

## 3. What we actually ran (so third parties can reproduce)

### 3.1 Local E2E benchmark against real `apache/airflow` PRs (**VERIFIED**)

1. Discover PRs in AIP-120 window → write [`corpus/cases.json`](corpus/cases.json)  
2. Materialize each PR’s **head file contents** into a mini git repo + Airflow-oriented `.aiv/`  
3. Run shaded `aiv-cli` with `--output-json`  
4. Publish [`reports/latest.html`](reports/latest.html) / [`latest.json`](latest.json)

```bash
git clone https://github.com/vaquarkhan/aiv-integrity-gate && cd aiv-integrity-gate
mvn -pl aiv-cli -am package -DskipTests
python benchmarks/airflow/scripts/run-e2e-benchmark.py discover \
  --repo apache/airflow --window 2026-06-20..2026-09-18 \
  --closed-limit 35 --merged-limit 12
python benchmarks/airflow/scripts/run-e2e-benchmark.py run
# open benchmarks/airflow/reports/latest.html
```

Label classes (full definitions in [`METHODOLOGY.md`](METHODOLOGY.md)):

- `synthetic_positive` / `objective_slop` — expect hard fail  
- `merged_control` / `synthetic_negative` — expect hard pass (fail = **FP**)  
- `closed_unmerged_sample` — **flag-rate only**; not AI-slop ground truth  

### 3.2 Fork install smoke (**VERIFIED**)

On [`vaquarkhan/airflow`](https://github.com/vaquarkhan/airflow) PR [#1](https://github.com/vaquarkhan/airflow/pull/1), workflow **AIV Gate (Airflow fork)** completed **success**:  
https://github.com/vaquarkhan/airflow/actions/runs/35455730891  

Log excerpt (**VERIFIED** from Actions log): Overall **PASS** (density, design, invariant, syntax) with `--label-pr-on-advisory aiv:ai-slop`.

That smoke tests **wiring**, not the 51-case precision table.

### 3.3 “Fetch all AI-slop PRs named in the email”

**NOT CLAIMED / not available:** the discuss mail and AIP-120 cite **aggregates** (4009 / 508 / …). They do **not** enumerate PR numbers labeled as AI-generated.  

GitHub issue-search for phrases like `Generated by ChatGPT` or `<<<<<<< SEARCH` returns non-zero totals, but **patch spot-checks** on returned PRs found **no** matching patch text (**VERIFIED** in methodology notes / prior scan practice). Those search totals must **not** be used as an AI-slop count.

What we **did** instead: scan **file patches** of closed-unmerged PRs for objective markers AIV would hard-block → **0 / 597** in the full census index (`census-index.json`) and **0 / 35** in the earlier sample.

### 3.4 Full closed-unmerged census + live bench repo (**VERIFIED** 2026-09-19)

| Artifact | Result |
|----------|--------|
| Index | **597** closed-unmerged + **30** merged controls in window (`corpus/census-index.json`); **0** objective patch markers |
| Score | **631** cases (`reports/census-latest.json`), `materialize_mode=patch`, syntax gate **off** (`config-census-patch.yaml`) |
| **VERIFIED** block rate | **1 / 596** closed-unmerged hard-fail = **0.17%** (blocking gate: `invariant`) — PR [#70053](https://github.com/apache/airflow/pull/70053) |
| Merged-control FP | **0%** (31 TN) |
| Synthetic recall | **100%** (3/3) |
| **ESTIMATED** vs AIP-120 508 | ~**0.9** PRs @ same rate; ~**664** job-min @ 779 mean — **not** a measured Actions bill |
| Live bench | [`vaquarkhan/aiv-airflow-bench`](https://github.com/vaquarkhan/aiv-airflow-bench) PRs 1–4 (synthetics + clean); snapshot inject script for real PR heads |

Reproduce:

```powershell
python benchmarks/airflow/scripts/run-full-census.py index --closed-cap 1000 --merged-cap 30 --replace-cases
$env:AIV_CENSUS_MATERIALIZE = "patch"
$env:AIV_CENSUS_AIV_CONFIG = "benchmarks/airflow/.aiv/config-census-patch.yaml"
python benchmarks/airflow/scripts/run-full-census.py score --resume
# open benchmarks/airflow/reports/census-latest.html
```

Every scored case keeps `provenance.json` (origin URL, label, workspace files) under `reports/census-*/`.

---

## 4. Safe statements for a larger group

**Safe to say (**VERIFIED**):**

1. Airflow has a large external PR / closed-unmerged volume problem in the AIP-120 window; independent Search API totals are the same order of magnitude (~4k opens; **597** closed-unmerged unfiltered).  
2. AIV is aimed at **objective, deterministic** defects (won’t-parse, conflict / edit-artifact markers, attribution strings), not “is this valuable?”  
3. On the published 51-case benchmark: **100%** recall on 3 synthetic objective positives; **0%** hard FP on 13 merged/clean controls; **2.9%** hard-flag rate on 35 closed-unmerged without objective markers (1 placeholder/`FIXME` hit).  
4. On the **full census** (596 closed-unmerged scored, patch materialize, syntax off): hard-fail **0.17%** (1 PR: #70053 / invariant); merged-control FP **0%**; synthetic recall **100%**.  
5. **ESTIMATED** only: applying that 0.17% rate to AIP-120’s 508 ≈ **0.9** PRs / ~**664** job-min @ 779 mean — assumptions listed in `census-latest.html`.  
6. Soft signals can **label** a PR (`severity: warn` + `--label-pr-on-advisory`) without failing CI.

**Unsafe (**NOT CLAIMED** — do not say):**

- “AIV would cut Airflow CI cost by X%” as a **measured** bill.  
- “Y% of the 508 are AI slop and AIV catches them.”  
- “We measured zero false positives on all Airflow PRs” (syntax-on / head materialize differs).  
- “Issue search found N ChatGPT PRs.”  
- “AIV solves AIP-120” or replaces fork-CI / issue-gating.

---

## 5. How AIV could help *alongside* AIP-120 (not instead) — **OPINION**

If the community keeps project CI expensive and wants a **cheap first sieve** before full matrix (whether on project runners or forks):

1. Run AIV on the PR diff in seconds (deterministic).  
2. **Hard-fail** only on high-precision objective defects.  
3. Keep density / fuzzy “slop vibe” as **warn + label**, never as the only merge blocker.  
4. Ship **added-lines-only** for placeholder rules before treating invariant TBD/FIXME as production-ready on Airflow-scale trees (motivated by #73124).  
5. Grow a **hand-labeled** true-positive set (PRs that fail parse/conflict/real defect) before quoting catch-rate or minutes saved.

That framing matches Ferruzzi’s concern (LLM volume is real) without pretending a marker gate clears 508 abandoned PRs, and without putting Magpie/LLM judgment on the hard path.

---

## 6. Pointers

| Resource | URL |
|----------|-----|
| AIP-120 wiki | https://cwiki.apache.org/confluence/spaces/AIRFLOW/pages/451974711/AIP-120+Run+CI+for+external+contributions+in+contributor+forks |
| Discuss thread | https://lists.apache.org/thread/w74655o8y1jzrzcnygntyyovocf7zmzj |
| AIV repo | https://github.com/vaquarkhan/aiv-integrity-gate |
| HTML report (51-case) | https://github.com/vaquarkhan/aiv-integrity-gate/blob/main/benchmarks/airflow/reports/latest.html |
| Full census HTML | https://github.com/vaquarkhan/aiv-integrity-gate/blob/main/benchmarks/airflow/reports/census-latest.html |
| Bench repo (live PRs) | https://github.com/vaquarkhan/aiv-airflow-bench |
| Proof / claim tags | [`PROOF-CLAIMS.md`](PROOF-CLAIMS.md) |
| JSON metrics | https://github.com/vaquarkhan/aiv-integrity-gate/blob/main/benchmarks/airflow/reports/latest.json |
| Fork smoke run | https://github.com/vaquarkhan/airflow/actions/runs/35455730891 |

---

## 7. Bottom line (one paragraph)

**VERIFIED:** Full AIP-120-window census scored **596** closed-unmerged Airflow PRs (patch materialize; syntax off) with **0.17%** hard-fail rate (1/596), **0%** merged-control FP, **100%** synthetic recall, and **0/597** objective patch markers in the index — so marker-based hard gates are a **narrow** pre-filter, not an explanation of the 508 volume. **ESTIMATED:** same rate × 508 × 779 ≈ **0.9** PRs / **664** job-min (assumptions in report). **NOT CLAIMED:** that closed-unmerged equals AI slop, or a measured Actions dollar savings. **OPINION:** AIV remains a precision pre-filter complementary to AIP-120 / issue-gating.
