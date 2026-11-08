(function () {
  if (window.__AIV_SITE_NAV__) return;
  window.__AIV_SITE_NAV__ = true;

  var script = document.currentScript;
  var depth = Number((script && script.getAttribute("data-depth")) || "0");
  if (!Number.isFinite(depth) || depth < 0) depth = 0;
  var root = depth === 0 ? "./" : "../".repeat(depth);
  var page = (script && script.getAttribute("data-page")) || "";

  function href(path) {
    return root + path.replace(/^\.\//, "");
  }

  var links = [
    { id: "home", label: "Home", path: "index.html" },
    { id: "tutorials", label: "Tutorials", path: "tutorials/index.html" },
    { id: "docs", label: "Docs", path: "guide/index.html" },
    { id: "walkthrough", label: "Walkthrough", path: "airflow-benchmark/index.html" },
    { id: "results", label: "2,098-file run", path: "benchmark/index.html" },
    { id: "slides", label: "Slides", path: "slides/index.html" },
    { id: "dashboard", label: "Dashboard", path: "dashboard/index.html" },
    { id: "source", label: "Source", path: "https://github.com/vaquarkhan/aiv-integrity-gate", external: true }
  ];

  var nav = document.createElement("header");
  nav.className = "aiv-chrome";
  nav.setAttribute("role", "banner");
  var inner = document.createElement("div");
  inner.className = "inner";

  var brand = document.createElement("a");
  brand.className = "brand";
  brand.href = href("index.html");
  brand.innerHTML = 'AIV Integrity Gate<small>Evidence gate · benchmark · deck</small>';
  inner.appendChild(brand);

  var menu = document.createElement("nav");
  menu.setAttribute("aria-label", "Site");
  links.forEach(function (item) {
    var a = document.createElement("a");
    a.textContent = item.label;
    a.href = item.external ? item.path : href(item.path);
    if (item.external) {
      a.target = "_blank";
      a.rel = "noopener";
    }
    if (page && page === item.id) a.setAttribute("aria-current", "page");
    menu.appendChild(a);
  });
  inner.appendChild(menu);
  nav.appendChild(inner);

  document.body.insertBefore(nav, document.body.firstChild);
  document.body.classList.add("aiv-has-chrome");

  // Remove duplicate page-local site headers (keep slide toolbar / dashboard tools).
  Array.prototype.slice.call(document.querySelectorAll("header.site, header.topbar")).forEach(function (el) {
    el.remove();
  });
  var slideHeader = document.querySelector("body > header:not(.aiv-chrome)");
  if (slideHeader && document.getElementById("deck")) slideHeader.remove();
  var dashBrand = document.querySelector(".app-header .brand");
  if (dashBrand) dashBrand.classList.add("dashboard-legacy-brand");
})();
