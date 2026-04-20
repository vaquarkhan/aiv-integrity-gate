# AIV static dashboard

This folder contains a **client-only** dashboard: **HTML**, **CSS**, and **JavaScript** (plus **sample-data.json**). There is no backend. All processing happens in the browser.

## Features

- **Light and dark** themes with persisted preference (`localStorage`) and support for `prefers-color-scheme`.
- **Charts** (Chart.js from CDN): pass vs fail, rolling pass rate, failures by gate, optional duration bars, stacked gate failures over time.
- **KPI cards** and a **recent runs** table (up to 50 rows).
- **Load sample data** or **upload a JSON** file matching the schema below.

## Schema (`schema_version: 1`)

```json
{
  "schema_version": 1,
  "runs": [
    {
      "timestamp": "2026-04-14T09:00:00Z",
      "branch": "main",
      "commit": "abc1234",
      "overall_pass": true,
      "duration_ms": 3200,
      "gates": [
        { "id": "density", "passed": true },
        { "id": "design", "passed": true, "message": "OK" }
      ]
    }
  ]
}
```

When the CLI gains a **`--output json`** (or similar) export compatible with this schema, you can append CI artifacts and feed them to the dashboard.

## How to view

Serving over **HTTP** is recommended so `fetch("sample-data.json")` works:

```bash
# Example: Node.js static server
npx --yes serve .

# Or Python
python -m http.server 8080
```

Then open `http://localhost:8080` (or the printed URL).

Opening **`index.html` via `file://`** still loads a small embedded fallback dataset if `sample-data.json` cannot be fetched.

## Files

| File | Role |
|------|------|
| `index.html` | Page structure and chart layout |
| `dashboard.css` | Theme variables and layout |
| `dashboard.js` | Data loading, Chart.js setup, theme toggle |
| `sample-data.json` | Demo dataset |

## Privacy

Uploaded JSON is read with the **File API** in the browser; nothing is sent to a server unless you host one yourself.

**Author:** Vaquar Khan
