(function () {
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

  async function loadJSON(path) {
    const res = await fetch(path);
    if (!res.ok) throw new Error("Failed to load " + path + " (" + res.status + ")");
    return res.json();
  }

  function pct(n) {
    return (Math.round(n * 1000) / 10).toFixed(n >= 0.1 || n === 0 ? 1 : 1) + "%";
  }

  function hardOf(c) {
    if (c.aiv_head_full && typeof c.aiv_head_full.hard_fail === "boolean") {
      return c.aiv_head_full.hard_fail;
    }
    return !!(c.aiv && c.aiv.hard_fail);
  }

  function blocksOf(c) {
    const src = c.aiv_head_full || c.aiv || {};
    return (src.blocking_gates || []).join(", ");
  }

  function advOf(c) {
    const src = c.aiv_head_full || c.aiv || {};
    return (src.advisory_gates || []).join(", ");
  }

  function renderBars(byLabel, total) {
    const order = [
      ["abandoned_clean", "fill"],
      ["ai_disclosed", "fill info"],
      ["ai_signal_advisory", "fill warn"],
      ["ai_slop_objective", "fill fail"],
      ["ai_signal_hard", "fill fail"],
    ];
    const host = $("#label-bars");
    host.innerHTML = "";
    for (const [key, cls] of order) {
      const n = byLabel[key] || 0;
      if (!n && key !== "abandoned_clean" && key !== "ai_disclosed") continue;
      const row = document.createElement("div");
      row.className = "bar-row";
      row.innerHTML =
        '<div class="name">' +
        key +
        '</div><div class="track"><div class="fill ' +
        cls.replace("fill ", "") +
        '" data-w="' +
        (total ? (n / total) * 100 : 0) +
        '"></div></div><div class="n">' +
        n +
        "</div>";
      // fix class
      row.querySelector(".fill").className = cls;
      host.appendChild(row);
    }
    requestAnimationFrame(() => {
      $$(".fill", host).forEach((el) => {
        el.style.width = (el.getAttribute("data-w") || "0") + "%";
      });
    });
  }

  function renderAbandonedTable(cases, filter) {
    const tbody = $("#abandoned-body");
    const filtered = cases.filter((c) => filter === "all" || c.label === filter);
    tbody.innerHTML = filtered
      .map((c) => {
        const hard = hardOf(c);
        return (
          "<tr data-label=\"" +
          c.label +
          "\">" +
          "<td><a href=\"" +
          c.url +
          "\" target=\"_blank\" rel=\"noopener\">#" +
          c.pr +
          "</a></td>" +
          "<td><span class=\"tag tag-label\">" +
          c.label +
          "</span></td>" +
          "<td>" +
          (hard
            ? '<span class="tag tag-fail">HARD</span>'
            : '<span class="tag tag-pass">pass</span>') +
          "</td>" +
          "<td><code>" +
          (blocksOf(c) || "—") +
          "</code></td>" +
          "<td><code>" +
          (advOf(c) || "—") +
          "</code></td>" +
          "<td>" +
          (c.disclosure && c.disclosure.disclosed ? "yes" : "no") +
          "</td>" +
          "<td>" +
          (c.bench_pr_url
            ? '<a href="' + c.bench_pr_url + '" target="_blank" rel="noopener">#' + c.bench_pr + "</a>"
            : "—") +
          "</td>" +
          "<td>" +
          escapeHtml(c.title || "") +
          "</td>" +
          "</tr>"
        );
      })
      .join("");
    $("#abandoned-count").textContent = filtered.length + " / " + cases.length + " shown";
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function renderLabeledSummary(labeled) {
    const c = labeled.claims;
    $("#m-recall").textContent = pct(c.hard_gate_recall);
    $("#m-fp").textContent = pct(c.false_positive_rate);
    $("#m-precision").textContent = pct(c.hard_gate_precision);
    $("#m-n").textContent = String(c.n_cases);
    $("#labeled-statement").textContent = c.statement;
    $("#labeled-breakdown").innerHTML =
      "<li>Proper controls (expect PASS): 5</li>" +
      "<li>Objective AI-slop (expect FAIL): 30 — TP " +
      c.TP +
      " / FN " +
      c.FN +
      "</li>" +
      "<li>Mixed / advisory cohort: 15 — cohesion advisory " +
      c.advisory_hit +
      "/" +
      c.advisory_expected +
      "</li>";
  }

  function renderAbandonedSummary(abandoned) {
    const c = abandoned.claims;
    $("#m-clean").textContent = c.abandoned_clean_pct + "%";
    $("#m-hard").textContent = c.hard_fail_among_sample + "/" + c.sampled_abandoned;
    $("#m-disclosed").textContent = c.disclosed_hard_caught + "/" + c.disclosed_n;
    $("#abandoned-statement").textContent = c.statement;
    $("#abandoned-bullets").innerHTML = (c.bullets || [])
      .map((b) => "<li>" + escapeHtml(b) + "</li>")
      .join("");
    $("#not-claimed").innerHTML = (c.not_claimed || [])
      .map((b) => "<li>" + escapeHtml(b) + "</li>")
      .join("");
    renderBars(c.by_label, c.sampled_abandoned);
    const stamp = abandoned.generated_at || "";
    $("#generated-at").textContent = stamp ? "Data generated " + stamp : "";
  }

  function renderChatgpt(gpt) {
    const c = gpt.claims;
    $("#chatgpt-statement").textContent = c.statement;
  }

  function wireFilters(cases) {
    $$(".chip").forEach((chip) => {
      chip.addEventListener("click", () => {
        $$(".chip").forEach((c) => c.classList.remove("active"));
        chip.classList.add("active");
        renderAbandonedTable(cases, chip.dataset.filter);
      });
    });
  }

  async function main() {
    const status = $("#load-status");
    try {
      const [product, labeled, abandoned, chatgpt] = await Promise.all([
        loadJSON("data/product-benchmark-latest.json"),
        loadJSON("data/labeled-latest.json"),
        loadJSON("data/abandoned-labeled-latest.json"),
        loadJSON("data/airflow-chatgpt-true-latest.json"),
      ]);
      renderLabeledSummary(labeled);
      renderAbandonedSummary(abandoned);
      renderChatgpt(chatgpt);
      renderAbandonedTable(abandoned.cases || [], "all");
      wireFilters(abandoned.cases || []);
      if (status) {
        status.textContent =
          "Loaded VERIFIED datasets · schema " +
          (labeled.schema_version || product.schema_version || "1");
      }
      window.__AIV_REPORT__ = { product, labeled, abandoned, chatgpt };
    } catch (err) {
      if (status) status.textContent = String(err.message || err);
      console.error(err);
    }
  }

  main();
})();
