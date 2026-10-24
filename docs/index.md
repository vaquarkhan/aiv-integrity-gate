---
title: AIV Integrity Gate
description: Deterministic evidence for agent-generated change.
---

<div class="aiv-hero">
  <p class="aiv-kicker">Start with the benchmark</p>
  <p class="aiv-lede">Turn an untrusted change into a policy-bound, machine-readable verdict before detailed review.</p>
  <div class="aiv-actions">
    <a class="aiv-btn aiv-btn-benchmark" href="airflow-benchmark/index.html">Open the benchmark</a>
    <a class="aiv-btn" href="benchmark/index.html">Measured results</a>
    <a class="aiv-btn" href="TUTORIAL.md">Quick start</a>
  </div>
</div>

<div class="aiv-kpis">
  <a class="aiv-kpi hot" href="benchmark/index.html"><span class="num">2,098</span><span class="lbl">stored patch files executed</span></a>
  <a class="aiv-kpi hot" href="benchmark/results.html"><span class="num">113</span><span class="lbl">blocked by a gate finding</span></a>
  <a class="aiv-kpi" href="benchmark/index.html"><span class="num">1,803</span><span class="lbl">passed. 182 could not be scored</span></a>
  <a class="aiv-kpi" href="airflow-benchmark/dataset.html"><span class="num">15,575</span><span class="lbl">Airflow rows indexed, not executed</span></a>
</div>

## Start here

<div class="aiv-cards">
  <a class="aiv-card hot" href="airflow-benchmark/index.html"><span class="step">Highlighted</span><h3>Open the benchmark</h3><p>The Airflow walkthrough. Screenshots, method, and what the full run measured.</p></a>
  <a class="aiv-card hot" href="benchmark/index.html"><span class="step">Full run</span><h3>Measured results</h3><p>Every stored patch file scored by AIV. 2,098 files, not the 55-case subset.</p></a>
  <a class="aiv-card" href="TUTORIAL.md"><span class="step">Docs</span><h3>Quick start</h3><p>Install the gate and read a verdict on one change.</p></a>
  <a class="aiv-card" href="HELLO-WORLD.md"><span class="step">Docs</span><h3>Hello World</h3><p>Every gate, one by one, on a small example.</p></a>
  <a class="aiv-card" href="GATES.md"><span class="step">Docs</span><h3>One page per gate</h3><p>What each gate checks and how a finding is reported.</p></a>
  <a class="aiv-card" href="MODULE-ADOPTION-GUIDE.md"><span class="step">Docs</span><h3>Which modules to use</h3><p>Pick the gates that match the change you want to review.</p></a>
  <a class="aiv-card" href="ARCHITECTURE.md"><span class="step">Docs</span><h3>Architecture</h3><p>One engine, the delivery surfaces, and how policy is pinned.</p></a>
  <a class="aiv-card" href="blog/AIV-VERIFIABLE-CHANGE.md"><span class="step">Docs</span><h3>Technical article</h3><p>The long-form explanation of verifiable change.</p></a>
</div>

## Every evidence gate

![AIV complete gate catalog](images/aiv-gate-catalog.png)

**Figure 1: Sixteen gates, four jobs.** Change-integrity and behavioral gates supply objective blocking candidates. Review-focus gates remain advisory. Governance surfaces are opt-in or advisory. Every result carries stable rule ids and severity.

![AIV complete architecture](images/aiv-architecture.png)

**Figure 2: One engine, multiple delivery surfaces.** Developers, CI, agents, AI-DLC, and policy hosts call the same gate engine. AIV freezes policy before evaluation and publishes one result to human, machine, GitHub, and audit surfaces.

## Trust the yardstick

![AIV immutable policy flow](images/aiv-trust-evidence.png)

**Figure 3: The proposed change cannot replace its own rules.** AIV pins one policy commit, freezes its `.aiv` tree, evaluates against that snapshot, and binds the same commit/bytes into evidence and audit metadata.

## Integrate

<div class="aiv-cards">
  <a class="aiv-card" href="DEPLOYMENT.md"><span class="step">Integrate</span><h3>Deployment and Action</h3><p>Run the gate in CI with the hardened GitHub Action.</p></a>
  <a class="aiv-card" href="PRE-COMMIT.md"><span class="step">Integrate</span><h3>Pre-commit</h3><p>Check a change before it is committed.</p></a>
  <a class="aiv-card" href="MCP.md"><span class="step">Integrate</span><h3>Strict verify</h3><p>The machine-readable contract for agents and hosts.</p></a>
  <a class="aiv-card" href="AIDLC.md"><span class="step">Integrate</span><h3>AI-DLC sensor</h3><p>Use the gate as a phase sensor or plugin.</p></a>
  <a class="aiv-card" href="DEVELOPER-CONFIGURATION.md"><span class="step">Integrate</span><h3>Configuration</h3><p>Policy files, severities, and rule settings.</p></a>
  <a class="aiv-card" href="EVIDENCE-AND-PROPOSALS.md"><span class="step">Integrate</span><h3>Audit and proposals</h3><p>Evidence, reports, and proposal operations.</p></a>
</div>

## Build and contribute

- [Developer guide](DEVELOPER-GUIDE.md)
- [Module dependency architecture](ARCHITECTURE.md#2-maven-project-and-module-dependency-architecture)
- [Documentation map](DOCUMENTATION-MAP.md)
- [GitHub source](https://github.com/vaquarkhan/aiv-integrity-gate)

Apache-2.0 licensed. Author: Viquar Khan.
