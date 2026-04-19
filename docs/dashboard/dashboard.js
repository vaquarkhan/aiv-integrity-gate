/**
 * AIV dashboard - expects JSON:
 * { "schema_version": 1, "runs": [ { "timestamp", "branch", "commit", "overall_pass",
 *   "duration_ms"?, "gates": [ { "id", "passed", "message"? } ] } ] }
 */
/* global Chart */

const STORAGE_KEY = "aiv-dashboard-theme";

const chartInstances = {};

function destroyCharts() {
  Object.values(chartInstances).forEach((c) => {
    try {
      c.destroy();
    } catch (_) {
      /* ignore */
    }
  });
  for (const k of Object.keys(chartInstances)) delete chartInstances[k];
}

function getThemePalette() {
  const s = getComputedStyle(document.documentElement);
  return {
    text: s.getPropertyValue("--chart-text").trim() || "#64748b",
    grid: s.getPropertyValue("--chart-grid").trim() || "rgba(0,0,0,0.06)",
    accent: s.getPropertyValue("--accent").trim() || "#2563eb",
    pass: s.getPropertyValue("--pass").trim() || "#059669",
    fail: s.getPropertyValue("--fail").trim() || "#dc2626",
    warn: s.getPropertyValue("--warn").trim() || "#d97706",
    muted: s.getPropertyValue("--text-muted").trim() || "#64748b",
  };
}

function initTheme() {
  const saved = localStorage.getItem(STORAGE_KEY);
  const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const theme = saved === "light" || saved === "dark" ? saved : prefersDark ? "dark" : "light";
  document.documentElement.dataset.theme = theme;
  updateThemeToggleLabel();
}

function toggleTheme() {
  const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  localStorage.setItem(STORAGE_KEY, next);
  updateThemeToggleLabel();
}

function updateThemeToggleLabel() {
  const btn = document.getElementById("theme-toggle");
  if (!btn) return;
  const dark = document.documentElement.dataset.theme === "dark";
  btn.setAttribute("aria-label", dark ? "Switch to light mode" : "Switch to dark mode");
  btn.textContent = dark ? "Light" : "Dark";
}

window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", (e) => {
  if (!localStorage.getItem(STORAGE_KEY)) {
    document.documentElement.dataset.theme = e.matches ? "dark" : "light";
    updateThemeToggleLabel();
    if (window.__aivLastData) renderDashboard(window.__aivLastData);
  }
});

function aggregateGateFailures(runs) {
  const counts = {};
  for (const run of runs) {
    if (!run.gates) continue;
    for (const g of run.gates) {
      if (!g.passed) {
        counts[g.id] = (counts[g.id] || 0) + 1;
      }
    }
  }
  return counts;
}

function buildCharts(data) {
  destroyCharts();
  const runs = [...(data.runs || [])].sort(
    (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
  );
  const labels = runs.map((r, i) => {
    const d = new Date(r.timestamp);
    const short = `${d.getMonth() + 1}/${d.getDate()}`;
    return r.branch ? `${short} ${String(r.branch).slice(0, 12)}` : `${short} #${i + 1}`;
  });
  const passRate = runs.map((r) => (r.overall_pass ? 100 : 0));
  const rolling = [];
  let wins = 0;
  runs.forEach((r, i) => {
    if (r.overall_pass) wins += 1;
    rolling.push((wins / (i + 1)) * 100);
  });

  const p = getThemePalette();
  const commonScale = {
    ticks: { color: p.text },
    grid: { color: p.grid },
  };

  const passCount = runs.filter((r) => r.overall_pass).length;
  const failCount = runs.length - passCount;

  const ctxDoughnut = document.getElementById("chart-doughnut");
  if (ctxDoughnut) {
    chartInstances.doughnut = new Chart(ctxDoughnut, {
      type: "doughnut",
      data: {
        labels: ["Pass", "Fail"],
        datasets: [
          {
            data: [passCount, failCount],
            backgroundColor: [p.pass, p.fail],
            borderWidth: 0,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: {
            position: "bottom",
            labels: { color: p.text },
          },
        },
      },
    });
  }

  const ctxLine = document.getElementById("chart-pass-rate");
  if (ctxLine) {
    chartInstances.line = new Chart(ctxLine, {
      type: "line",
      data: {
        labels,
        datasets: [
          {
            label: "Run pass (100% / 0%)",
            data: passRate,
            borderColor: p.accent,
            backgroundColor: `${p.accent}33`,
            fill: true,
            tension: 0.25,
            pointRadius: 3,
          },
          {
            label: "Rolling pass rate %",
            data: rolling,
            borderColor: p.pass,
            backgroundColor: "transparent",
            tension: 0.25,
            borderDash: [4, 4],
            pointRadius: 0,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: "index", intersect: false },
        plugins: {
          legend: {
            labels: { color: p.text },
          },
        },
        scales: {
          x: commonScale,
          y: {
            ...commonScale,
            min: 0,
            max: 100,
            ticks: {
              ...commonScale.ticks,
              callback: (v) => `${v}%`,
            },
          },
        },
      },
    });
  }

  const gateFails = aggregateGateFailures(runs);
  const gateIds = Object.keys(gateFails).sort((a, b) => gateFails[b] - gateFails[a]);
  const ctxBar = document.getElementById("chart-gate-fails");
  const gateBarEmpty = document.getElementById("chart-gate-fails-empty");
  if (ctxBar && gateBarEmpty) {
    const hasGateFails = gateIds.length > 0;
    gateBarEmpty.hidden = hasGateFails;
    ctxBar.hidden = !hasGateFails;
    if (hasGateFails) {
      chartInstances.bar = new Chart(ctxBar, {
        type: "bar",
        data: {
          labels: gateIds,
          datasets: [
            {
              label: "Failed runs per gate",
              data: gateIds.map((id) => gateFails[id]),
              backgroundColor: gateIds.map((_, i) =>
                i === 0 ? p.fail : `${p.fail}cc`
              ),
              borderRadius: 6,
            },
          ],
        },
        options: {
          indexAxis: "y",
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
          },
          scales: {
            x: {
              ...commonScale,
              beginAtZero: true,
              ticks: { stepSize: 1, color: p.text },
            },
            y: commonScale,
          },
        },
      });
    }
  }

  const durations = runs.map((r) =>
    typeof r.duration_ms === "number" ? r.duration_ms : null
  );
  const hasDuration = durations.some((d) => d != null);
  const durationCard = document.getElementById("duration-card");
  if (durationCard) durationCard.hidden = !hasDuration;
  const ctxDur = document.getElementById("chart-duration");
  if (ctxDur && hasDuration) {
    chartInstances.duration = new Chart(ctxDur, {
      type: "bar",
      data: {
        labels,
        datasets: [
          {
            label: "Duration (ms)",
            data: durations.map((d) => (d == null ? 0 : d)),
            backgroundColor: `${p.warn}99`,
            borderRadius: 4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { labels: { color: p.text } },
        },
        scales: {
          x: commonScale,
          y: { ...commonScale, beginAtZero: true },
        },
      },
    });
  }

  const matrixCard = document.getElementById("gates-matrix-card");
  const ctxStack = document.getElementById("chart-gates-matrix");
  if (matrixCard && ctxStack) {
    if (!runs.length || !runs[0].gates) {
      matrixCard.hidden = true;
    } else {
    const allGateIds = [
      ...new Set(runs.flatMap((r) => (r.gates || []).map((g) => g.id))),
    ].sort();
    const gatesWithFailures = allGateIds.filter((gid) =>
      runs.some((run) => (run.gates || []).some((g) => g.id === gid && !g.passed))
    );
    matrixCard.hidden = gatesWithFailures.length === 0;
    if (gatesWithFailures.length) {
      const datasets = gatesWithFailures.map((gid, idx) => {
        const hue = (idx * 47) % 360;
        return {
          label: gid,
          data: runs.map((run) => {
            const g = (run.gates || []).find((x) => x.id === gid);
            return g && !g.passed ? 1 : 0;
          }),
          backgroundColor: `hsla(${hue}, 65%, 55%, 0.85)`,
          stack: "fails",
        };
      });
      chartInstances.stack = new Chart(ctxStack, {
        type: "bar",
        data: { labels, datasets },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: {
              position: "bottom",
              labels: { color: p.text, boxWidth: 12 },
            },
          },
          scales: {
            x: {
              stacked: true,
              ...commonScale,
              ticks: { maxRotation: 45, minRotation: 45, color: p.text },
            },
            y: {
              stacked: true,
              ...commonScale,
              beginAtZero: true,
              ticks: { stepSize: 1, color: p.text },
            },
          },
        },
      });
    }
    }
  }
}

function updateKpis(runs) {
  const total = runs.length;
  const passed = runs.filter((r) => r.overall_pass).length;
  const rate = total ? Math.round((passed / total) * 100) : 0;
  const gateFails = aggregateGateFailures(runs);
  const topGate = Object.entries(gateFails).sort((a, b) => b[1] - a[1])[0];

  setText("kpi-runs", String(total));
  setText("kpi-pass-rate", `${rate}%`);
  setText("kpi-fails", String(total - passed));
  setText(
    "kpi-top-gate",
    topGate ? `${topGate[0]} (${topGate[1]})` : "-"
  );
}

function setText(id, text) {
  const el = document.getElementById(id);
  if (el) el.textContent = text;
}

function fillTable(runs) {
  const tbody = document.querySelector("#runs-table tbody");
  if (!tbody) return;
  tbody.innerHTML = "";
  const sorted = [...runs].sort(
    (a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
  );
  for (const r of sorted.slice(0, 50)) {
    const tr = document.createElement("tr");
    const ts = new Date(r.timestamp).toISOString().replace("T", " ").slice(0, 19);
    const gates = (r.gates || [])
      .filter((g) => !g.passed)
      .map((g) => g.id)
      .join(", ");
    tr.innerHTML = `
      <td>${escapeHtml(ts)}</td>
      <td>${escapeHtml(r.branch || "-")}</td>
      <td><code>${escapeHtml((r.commit || "").slice(0, 7))}</code></td>
      <td><span class="badge ${r.overall_pass ? "pass" : "fail"}">${r.overall_pass ? "PASS" : "FAIL"}</span></td>
      <td>${escapeHtml(gates || "-")}</td>
    `;
    tbody.appendChild(tr);
  }
}

function escapeHtml(s) {
  const d = document.createElement("div");
  d.textContent = s;
  return d.innerHTML;
}

function showError(msg) {
  const el = document.getElementById("status");
  if (!el) return;
  el.className = "status-banner error";
  el.textContent = msg;
  el.hidden = false;
}

function clearError() {
  const el = document.getElementById("status");
  if (!el) return;
  el.hidden = true;
  el.textContent = "";
}

function validateData(data) {
  if (!data || typeof data !== "object") return "Invalid JSON.";
  if (data.schema_version !== 1) return "Unsupported schema_version (expected 1).";
  if (!Array.isArray(data.runs)) return "Missing runs array.";
  return null;
}

function renderDashboard(data) {
  const err = validateData(data);
  if (err) {
    showError(err);
    return;
  }
  clearError();
  window.__aivLastData = data;
  const runs = data.runs || [];
  updateKpis(runs);
  fillTable(runs);
  buildCharts(data);
}

async function loadSample() {
  try {
    const res = await fetch("./sample-data.json", { cache: "no-store" });
    if (!res.ok) throw new Error(String(res.status));
    const data = await res.json();
    renderDashboard(data);
  } catch {
    renderDashboard(FALLBACK_SAMPLE);
  }
}

function onFile(ev) {
  const file = ev.target.files && ev.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const data = JSON.parse(String(reader.result));
      renderDashboard(data);
    } catch (e) {
      showError("Could not parse JSON: " + (e && e.message ? e.message : String(e)));
    }
  };
  reader.readAsText(file);
}

/** Used when sample-data.json cannot be fetched (e.g. file://) */
const FALLBACK_SAMPLE = {
  schema_version: 1,
  runs: [
    {
      timestamp: "2026-04-01T10:00:00Z",
      branch: "main",
      commit: "abc0001",
      overall_pass: true,
      duration_ms: 3200,
      gates: [
        { id: "density", passed: true },
        { id: "design", passed: true },
        { id: "dependency", passed: true },
      ],
    },
    {
      timestamp: "2026-04-02T10:00:00Z",
      branch: "feature/x",
      commit: "abc0002",
      overall_pass: false,
      duration_ms: 4100,
      gates: [
        { id: "density", passed: false },
        { id: "design", passed: true },
        { id: "dependency", passed: true },
      ],
    },
    {
      timestamp: "2026-04-03T10:00:00Z",
      branch: "feature/x",
      commit: "abc0003",
      overall_pass: true,
      duration_ms: 3600,
      gates: [
        { id: "density", passed: true },
        { id: "design", passed: true },
        { id: "dependency", passed: true },
      ],
    },
  ],
};

function boot() {
  initTheme();
  document.getElementById("theme-toggle")?.addEventListener("click", () => {
    toggleTheme();
    if (window.__aivLastData) renderDashboard(window.__aivLastData);
  });
  document.getElementById("load-sample")?.addEventListener("click", loadSample);
  document.getElementById("file-input")?.addEventListener("change", onFile);
  loadSample();
}

document.addEventListener("DOMContentLoaded", boot);
