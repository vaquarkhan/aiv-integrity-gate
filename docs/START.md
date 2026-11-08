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
    <a class="aiv-btn" href="slides/index.html">Presentation deck</a>
    <a class="aiv-btn" href="tutorials/index.html">Tutorials</a>
    <a class="aiv-btn" href="guide/index.html">All docs</a>
  </div>
</div>

<div class="aiv-kpis">
  <a class="aiv-kpi hot" href="benchmark/index.html"><span class="num">2,098</span><span class="lbl">stored patch files executed</span></a>
  <a class="aiv-kpi hot" href="benchmark/results.html"><span class="num">113</span><span class="lbl">blocked by a gate finding</span></a>
  <a class="aiv-kpi" href="benchmark/index.html"><span class="num">1,803</span><span class="lbl">passed. 182 could not be scored</span></a>
  <a class="aiv-kpi" href="benchmark/index.html"><span class="num">1,219</span><span class="lbl">Airflow patch files inside the 2,098-file run</span></a>
</div>

## Start here

<div class="aiv-cards">
  <a class="aiv-card hot" href="airflow-benchmark/index.html"><span class="step">Highlighted</span><h3>Open the benchmark</h3><p>The Airflow walkthrough. Screenshots, method, and what the full run measured.</p></a>
  <a class="aiv-card hot" href="benchmark/index.html"><span class="step">Full run</span><h3>Measured results</h3><p>Every stored patch file scored by AIV. 2,098 files, not the 55-case subset.</p></a>
  <a class="aiv-card" href="dashboard/index.html"><span class="step">Dashboard</span><h3>Run dashboard</h3><p>Sample data by default; switch to live AIV runs from GitHub Actions or a published report.</p></a>
  <a class="aiv-card hot" href="slides/index.html"><span class="step">Slides</span><h3>Presentation deck</h3><p>52 slides: the problem, the end-to-end flow, every gate with its configuration, and the benchmark. PDF and PowerPoint downloads.</p></a>
  <a class="aiv-card hot" href="tutorials/index.html"><span class="step">Start</span><h3>Tutorials index</h3><p>Ordered guides for first-time users. No downloads.</p></a>
  <a class="aiv-card hot" href="guide/index.html"><span class="step">Docs</span><h3>All documentation</h3><p>Gates, config, deployment, architecture, and more.</p></a>
  <a class="aiv-card" href="TUTORIAL/"><span class="step">Tutorial</span><h3>Quick start</h3><p>Install the gate and read a verdict on one change.</p></a>
  <a class="aiv-card" href="HELLO-WORLD/"><span class="step">Tutorial</span><h3>Hello World</h3><p>Every gate, one by one, on a small example.</p></a>
  <a class="aiv-card" href="GATES/"><span class="step">Docs</span><h3>One page per gate</h3><p>What each gate checks and how a finding is reported.</p></a>
  <a class="aiv-card" href="ARCHITECTURE/"><span class="step">Docs</span><h3>Architecture</h3><p>One engine, the delivery surfaces, and how policy is pinned.</p></a>
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
  <a class="aiv-card" href="DEPLOYMENT/"><span class="step">Integrate</span><h3>Deployment and Action</h3><p>Run the gate in CI with the hardened GitHub Action.</p></a>
  <a class="aiv-card" href="PRE-COMMIT/"><span class="step">Integrate</span><h3>Pre-commit</h3><p>Check a change before it is committed.</p></a>
  <a class="aiv-card" href="MCP/"><span class="step">Integrate</span><h3>Strict verify</h3><p>The machine-readable contract for agents and hosts.</p></a>
  <a class="aiv-card" href="AIDLC/"><span class="step">Integrate</span><h3>AI-DLC sensor</h3><p>Use the gate as a phase sensor or plugin.</p></a>
  <a class="aiv-card" href="DEVELOPER-CONFIGURATION/"><span class="step">Integrate</span><h3>Configuration</h3><p>Policy files, severities, and rule settings.</p></a>
  <a class="aiv-card" href="EVIDENCE-AND-PROPOSALS/"><span class="step">Integrate</span><h3>Audit and proposals</h3><p>Evidence, reports, and proposal operations.</p></a>
</div>

## Build and contribute

- [Developer guide](DEVELOPER-GUIDE/)
- [Module dependency architecture](ARCHITECTURE/#2-maven-project-and-module-dependency-architecture)
- [Documentation map](DOCUMENTATION-MAP/)
- [Tutorials index](tutorials/index.html)
- [Docs index](guide/index.html)
- [GitHub source](https://github.com/vaquarkhan/aiv-integrity-gate)

Apache-2.0 licensed. Author: Viquar Khan.
