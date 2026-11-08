# AIV dashboard

A client-only dashboard (HTML, CSS, JavaScript) for AIV run history. There is no backend; everything runs in the browser. Published with the docs site at `dashboard/index.html`.

## Data sources

The page always shows which source it is displaying, in a badge above the KPIs. It never mixes sources and never fills missing data with sample values.

| Source | What it shows | How to open it |
|---|---|---|
| **Sample data** (default) | 25 invented runs (`sample-data.json`) for a demonstration. Not results of a real repository. | `dashboard/index.html` |
| **Live: GitHub Actions** | Real completed runs of the AIV workflow in a public repository: pass or fail, branch, commit, start time, duration, and a link to each run. Run-level only. | `dashboard/index.html?source=github&repo=owner/name&workflow=aiv.yml` |
| **Live: published report JSON** | Real runs with per-gate results from a JSON file you publish over https. | `dashboard/index.html?source=url&url=https://...` |
| **Loaded file** | A JSON file chosen on this computer. Read in the browser, never uploaded. | "Load JSON file" button |

The GitHub source uses the public REST API (`/repos/{owner}/{repo}/actions/workflows/{workflow}/runs`). Anonymous requests are limited to 60 per hour per visitor, so results are cached in the browser session for 5 minutes, and the page explains when the limit is reached. Only `success` and `failure` conclusions are counted; cancelled and skipped runs are left out.

## Accepted JSON

Any of these:

1. Dashboard schema 1:

   ```json
   {
     "schema_version": 1,
     "runs": [
       {
         "timestamp": "2026-10-24T04:40:28Z",
         "branch": "main",
         "commit": "ab2aa80",
         "overall_pass": true,
         "duration_ms": 114000,
         "gates": [ { "id": "invariant", "passed": true }, { "id": "syntax", "passed": true } ]
       }
     ]
   }
   ```

2. One AIV report written by `--output-json` (it has `passed` and a `gates` array). It becomes one run.
3. A list of `{ "timestamp", "branch", "commit", "duration_ms", "report": <AIV --output-json report> }` entries, one per run. This is the shape to publish when you want per-gate history: append each CI run's report to the list.

## Security

- Content Security Policy: scripts only from this site, no inline script; network reads only from this site, `api.github.com`, `raw.githubusercontent.com` and `*.github.io`. A report URL on another host is refused by the browser.
- Chart.js 4.4.1 (MIT, `vendor/LICENSE-chart.js.md`) is served from `vendor/`, not a CDN. SHA-256 of `vendor/chart.umd.min.js`: `d2af8974e95271638772e9e9524db5b9a6f58d6ec2d5d781400447b4a31c681e`.
- Repository and workflow inputs are validated before any request. Table text is HTML-escaped, and run links are only rendered for `https://github.com/` URLs.
- No token is ever requested or stored.

## View locally

```bash
python -m http.server 8080 --directory docs
# then open http://localhost:8080/dashboard/
```

## Files

| File | Role |
|------|------|
| `index.html` | Page structure, data-source selector, chart layout |
| `dashboard.css` | Theme variables and layout |
| `dashboard.js` | Data sources, validation, Chart.js setup, theme toggle |
| `sample-data.json` | Invented demonstration runs |
| `vendor/chart.umd.min.js` | Chart.js 4.4.1 |

**Author:** Viquar Khan
