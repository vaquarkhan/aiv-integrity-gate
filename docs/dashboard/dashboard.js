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
  const gateCard = ctxBar ? ctxBar.closest(".chart-card") : null;
  if (gateCard) gateCard.hidden = !runs.some((r) => Array.isArray(r.gates));
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
            label: "Duration (seconds)",
            data: durations.map((d) => (d == null ? 0 : Math.round(d / 100) / 10)),
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
  const hasGates = runs.some((r) => Array.isArray(r.gates));
  setText(
    "kpi-top-gate",
    topGate ? `${topGate[0]} (${topGate[1]})` : hasGates ? "none failed" : "no gate data"
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
    const safeUrl = typeof r.url === "string" && r.url.startsWith("https://github.com/") ? r.url : "";
    const commit = escapeHtml((r.commit || "").slice(0, 7));
    tr.innerHTML = `
      <td>${escapeHtml(ts)}</td>
      <td>${escapeHtml(r.branch || "-")}</td>
      <td>${safeUrl ? `<a href="${escapeHtml(safeUrl)}" rel="noopener noreferrer"><code>${commit}</code></a>` : `<code>${commit}</code>`}</td>
      <td><span class="badge ${r.overall_pass ? "pass" : "fail"}">${r.overall_pass ? "PASS" : "FAIL"}</span></td>
      <td>${escapeHtml(gates || (Array.isArray(r.gates) ? "-" : "n/a"))}</td>
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
  for (const r of data.runs) {
    if (!r || typeof r !== "object" || Number.isNaN(new Date(r.timestamp).getTime())) {
      return "Every run needs a valid timestamp.";
    }
  }
  return null;
}

// ---------------------------------------------------------------- data sources
// sample: invented demo runs (default). github: real AIV Gate runs from the public GitHub Actions API.
// url: a published JSON file with real runs (dashboard schema 1, or AIV --output-json reports).
// file: a JSON file chosen on this computer. The page never mixes sources and never fills gaps with sample data.

const DEFAULTS = { repo: "vaquarkhan/aiv-integrity-gate", workflow: "aiv.yml" };
const REPO_RE = /^[A-Za-z0-9-]{1,39}\/[A-Za-z0-9._-]{1,100}$/;
const WORKFLOW_RE = /^([A-Za-z0-9._-]{1,100}\.ya?ml|\d{1,12})$/;
const CACHE_TTL_MS = 5 * 60 * 1000;
const MAX_RUNS = 100;

function setSource(kind, detail) {
  const badge = document.getElementById("source-badge");
  const note = document.getElementById("source-note");
  const labels = {
    sample: "Sample data",
    github: "Live: GitHub Actions",
    url: "Live: published report",
    file: "Loaded file",
  };
  if (badge) {
    badge.textContent = labels[kind] || kind;
    badge.className = "source-badge " + (kind === "sample" ? "sample" : "live");
  }
  if (note) note.textContent = detail || "";
  document.documentElement.dataset.source = kind;
}

function renderDashboard(data, source, detail) {
  const err = validateData(data);
  if (err) {
    showError(err);
    return false;
  }
  clearError();
  window.__aivLastData = data;
  if (source) {
    window.__aivLastSource = [source, detail];
    setSource(source, detail);
  }
  const runs = data.runs || [];
  updateKpis(runs);
  fillTable(runs);
  buildCharts(data);
  const gateNote = document.getElementById("gate-data-note");
  if (gateNote) gateNote.hidden = runs.some((r) => Array.isArray(r.gates));
  return true;
}

async function loadSample() {
  const detail = "Invented runs for a demonstration. They are not results of a real repository.";
  try {
    const res = await fetch("./sample-data.json", { cache: "no-store" });
    if (!res.ok) throw new Error(String(res.status));
    renderDashboard(await res.json(), "sample", detail);
  } catch {
    renderDashboard(FALLBACK_SAMPLE, "sample", detail);
  }
}

/** Convert one GitHub workflow run to a dashboard run. Only completed success/failure runs count. */
function fromWorkflowRun(r) {
  if (r.status !== "completed" || (r.conclusion !== "success" && r.conclusion !== "failure")) return null;
  const start = new Date(r.run_started_at || r.created_at).getTime();
  const end = new Date(r.updated_at).getTime();
  return {
    timestamp: r.run_started_at || r.created_at,
    branch: r.head_branch || "",
    commit: r.head_sha || "",
    overall_pass: r.conclusion === "success",
    duration_ms: Number.isFinite(end - start) && end >= start ? end - start : undefined,
    url: r.html_url,
    event: r.event,
  };
}

function cacheGet(key) {
  try {
    const hit = JSON.parse(sessionStorage.getItem(key) || "null");
    return hit && Date.now() - hit.at < CACHE_TTL_MS ? hit : null;
  } catch {
    return null;
  }
}

function cachePut(key, value) {
  try {
    sessionStorage.setItem(key, JSON.stringify({ at: Date.now(), value }));
  } catch {
    /* storage full or disabled: run without a cache */
  }
}

async function loadGithub(repo, workflow) {
  if (!REPO_RE.test(repo)) return showError("Repository must look like owner/name.");
  if (!WORKFLOW_RE.test(workflow)) return showError("Workflow must be a file name like aiv.yml or a numeric id.");
  const key = `aiv-gh:${repo}:${workflow}`;
  const cached = cacheGet(key);
  let payload = cached && cached.value;
  let fetchedAt = cached ? new Date(cached.at) : null;
  if (!payload) {
    setStatus(`Loading AIV runs for ${repo} from GitHub...`);
    const url = `https://api.github.com/repos/${repo}/actions/workflows/${encodeURIComponent(workflow)}/runs?per_page=${MAX_RUNS}&status=completed`;
    let res;
    try {
      res = await fetch(url, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" });
    } catch (e) {
      return showError("Could not reach api.github.com: " + (e && e.message ? e.message : String(e)));
    }
    if (res.status === 403 || res.status === 429) {
      const reset = Number(res.headers.get("x-ratelimit-reset"));
      const when = reset ? new Date(reset * 1000).toLocaleTimeString() : "later";
      return showError(`GitHub's anonymous rate limit (60 requests per hour) is used up. Try again after ${when}.`);
    }
    if (res.status === 404) return showError(`No public workflow '${workflow}' found in ${repo}.`);
    if (!res.ok) return showError(`GitHub returned HTTP ${res.status}.`);
    const body = await res.json();
    payload = (body.workflow_runs || []).map(fromWorkflowRun).filter(Boolean);
    fetchedAt = new Date();
    cachePut(key, payload);
  }
  if (!payload.length) return showError(`No completed success or failure runs of ${workflow} in ${repo} yet.`);
  renderDashboard({ schema_version: 1, runs: payload }, "github",
    `${payload.length} completed runs of ${workflow} in ${repo}, fetched ${fetchedAt.toLocaleString()}. ` +
    "Run-level results only; per-gate results need a published AIV report.");
}

/** Accept dashboard schema 1, one AIV --output-json report, or a list of {timestamp, commit, branch, report}. */
function normalizeReportJson(data) {
  if (data && data.schema_version === 1 && Array.isArray(data.runs)) return data;
  const toRun = (rep, meta) => ({
    timestamp: (meta && meta.timestamp) || rep.generated_at || new Date().toISOString(),
    branch: (meta && meta.branch) || "",
    commit: (meta && meta.commit) || rep.head_sha || "",
    overall_pass: rep.passed === true,
    duration_ms: meta && typeof meta.duration_ms === "number" ? meta.duration_ms : undefined,
    gates: (rep.gates || []).map((g) => ({ id: String(g.id), passed: g.passed === true, message: g.message || "" })),
  });
  if (data && Array.isArray(data.gates) && typeof data.passed === "boolean") {
    return { schema_version: 1, runs: [toRun(data, null)] };
  }
  if (Array.isArray(data)) {
    return {
      schema_version: 1,
      runs: data.filter((x) => x && x.report && Array.isArray(x.report.gates)).map((x) => toRun(x.report, x)),
    };
  }
  return data;
}

async function loadUrl(raw) {
  let u;
  try {
    u = new URL(raw, window.location.href);
  } catch {
    return showError("That is not a valid URL.");
  }
  if (u.protocol !== "https:" && u.origin !== window.location.origin) return showError("Use an https URL.");
  setStatus("Loading " + u.href + " ...");
  try {
    const res = await fetch(u.href, { cache: "no-store", credentials: "omit" });
    if (!res.ok) return showError(`HTTP ${res.status} from ${u.host}.`);
    const data = normalizeReportJson(await res.json());
    renderDashboard(data, "url", `${(data.runs || []).length} runs from ${u.href}, fetched ${new Date().toLocaleString()}.`);
  } catch (e) {
    showError(`Could not load ${u.host}: ${e && e.message ? e.message : String(e)}. ` +
      "The host must allow cross-origin reads and be listed in this page's connect-src policy.");
  }
}

function setStatus(msg) {
  const el = document.getElementById("status");
  if (!el) return;
  el.className = "status-banner";
  el.textContent = msg;
  el.hidden = false;
}

function onFile(ev) {
  const file = ev.target.files && ev.target.files[0];
  if (!file) return;
  if (file.size > 20 * 1024 * 1024) return showError("File is larger than 20 MB.");
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const data = normalizeReportJson(JSON.parse(String(reader.result)));
      renderDashboard(data, "file", `${(data.runs || []).length} runs from ${file.name} (read in this browser, not uploaded).`);
    } catch (e) {
      showError("Could not parse JSON: " + (e && e.message ? e.message : String(e)));
    }
  };
  reader.readAsText(file);
}

/** Read ?source=github&repo=&workflow= or ?source=url&url= and keep the form in sync. */
function loadFromParams() {
  const q = new URLSearchParams(window.location.search);
  const source = q.get("source") || "sample";
  const repo = q.get("repo") || DEFAULTS.repo;
  const workflow = q.get("workflow") || DEFAULTS.workflow;
  const url = q.get("url") || "";
  const sel = document.getElementById("source-select");
  if (sel) sel.value = ["sample", "github", "url"].includes(source) ? source : "sample";
  const set = (id, v) => {
    const el = document.getElementById(id);
    if (el) el.value = v;
  };
  set("repo-input", repo);
  set("workflow-input", workflow);
  set("url-input", url);
  syncSourceFields();
  if (source === "github") return loadGithub(repo, workflow);
  if (source === "url" && url) return loadUrl(url);
  return loadSample();
}

function syncSourceFields() {
  const v = document.getElementById("source-select")?.value || "sample";
  document.querySelectorAll("[data-for]").forEach((el) => {
    el.hidden = el.dataset.for !== v;
  });
}

function applySourceForm(ev) {
  if (ev) ev.preventDefault();
  const v = document.getElementById("source-select")?.value || "sample";
  const q = new URLSearchParams();
  if (v !== "sample") q.set("source", v);
  if (v === "github") {
    q.set("repo", document.getElementById("repo-input").value.trim());
    q.set("workflow", document.getElementById("workflow-input").value.trim());
  }
  if (v === "url") q.set("url", document.getElementById("url-input").value.trim());
  const qs = q.toString();
  history.replaceState(null, "", qs ? `?${qs}` : window.location.pathname);
  loadFromParams();
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
  document.getElementById("load-sample")?.addEventListener("click", () => {
    const sel = document.getElementById("source-select");
    if (sel) sel.value = "sample";
    applySourceForm();
  });
  document.getElementById("file-input")?.addEventListener("change", onFile);
  document.getElementById("source-select")?.addEventListener("change", syncSourceFields);
  document.getElementById("source-form")?.addEventListener("submit", applySourceForm);
  if (typeof Chart === "undefined") {
    showError("Chart library failed to load; tables and KPIs still work.");
  }
  loadFromParams();
}

document.addEventListener("DOMContentLoaded", boot);
