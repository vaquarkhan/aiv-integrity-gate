# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Generate AIV product architecture SVG and PNG assets from one source."""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parent
NAVY = "#0B132B"
TEAL = "#0F766E"
MINT = "#DFF7F2"
BLUE = "#E8F1FF"
AMBER = "#FFF4D6"
RED = "#FEECEC"
SLATE = "#475569"
LINE = "#CBD5E1"
WHITE = "#FFFFFF"


def start(title, subtitle, width=1200, height=700):
    return [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" font-family="Segoe UI,Arial,sans-serif">',
            '<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6 Z" fill="#475569"/></marker></defs>',
            f'<rect width="{width}" height="{height}" fill="{WHITE}"/>',
            f'<text x="40" y="48" font-size="28" font-weight="700" fill="{NAVY}">{escape(title)}</text>',
            f'<text x="40" y="76" font-size="15" fill="{SLATE}">{escape(subtitle)}</text>']


def box(svg, x, y, w, h, title, lines=(), fill=WHITE, stroke=LINE, title_color=NAVY, center=False):
    svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
    anchor = "middle" if center else "start"
    tx = x + w / 2 if center else x + 16
    svg.append(f'<text x="{tx}" y="{y+27}" text-anchor="{anchor}" font-size="15" font-weight="700" fill="{title_color}">{escape(title)}</text>')
    for i, line in enumerate(lines):
        svg.append(f'<text x="{tx}" y="{y+50+i*18}" text-anchor="{anchor}" font-size="12" fill="{SLATE}">{escape(line)}</text>')


def arrow(svg, x1, y1, x2, y2):
    svg.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{SLATE}" stroke-width="2" marker-end="url(#arrow)"/>')


def finish(name, svg):
    svg.append('</svg>')
    path = OUT / f"{name}.svg"
    path.write_text("\n".join(svg), encoding="utf-8")
    return path


def architecture():
    s = start("AIV Integrity Gate architecture", "One evidence engine, frozen policy, multiple delivery surfaces")
    callers = [(40, "Developer", "pre-commit"), (270, "CI / Action", "pull request"),
               (500, "Agent / MCP", "JSON verify"), (730, "AI-DLC", "phase sensor"), (960, "Policy engine", "facts / verdict")]
    for x, a, b in callers:
        box(s, x, 105, 200, 70, a, [b], BLUE, "#93C5FD", center=True)
        arrow(s, x+100, 175, x+100, 215)
    box(s, 40, 220, 1120, 95, "Trust boundary", [
        "Resolve config once | compare complete .aiv tree | reject ignored extras and symlinks",
        "Materialize immutable policy snapshot from trusted Git blobs | bind request + config + sidecars + commit"
    ], MINT, "#5EEAD4")
    arrow(s, 600, 315, 600, 350)
    box(s, 40, 355, 1120, 190, "Deterministic verdict engine", [
        "Hard evidence: syntax | invariant | dependency | package existence | test adequacy | security | design",
        "Review evidence: density | cohesion | duplication | documentation | PR metadata | license header",
        "Extension surfaces: generic YAML rules | trusted external SARIF | optional advisory Jev questions",
        "Core policy: severity | fail-fast | baselines | inline suppressions | signed allow-listed skip"
    ], "#F8FAFC", LINE)
    outputs = [(40, "Verdict JSON", "schema v2"), (270, "SARIF / Checks", "severity preserved"),
               (500, "PR feedback", "labels / comments"), (730, "Audit chain", "locked append"),
               (960, "HTML / JSONL", "calibration / query")]
    for x, a, b in outputs:
        arrow(s, x+100, 545, x+100, 580)
        box(s, x, 585, 200, 70, a, [b], AMBER, "#F59E0B", center=True)
    return finish("aiv-architecture", s)


def gate_catalog():
    s = start("Every gate has one evidence job", "Hard blockers require objective proof; review signals stay advisory")
    groups = [
        (40, 110, "Change integrity", MINT, [
            ("syntax", "strict parse or visible unverified"), ("invariant", "conflicts, placeholders, paste artifacts"),
            ("dependency", "imports backed by manifests"), ("package-existence", "declared package found / absent / unverified")]),
        (330, 110, "Behavioral evidence", BLUE, [
            ("test-adequacy", "added tests contain assertion evidence"), ("security", "added-line credential tripwire"),
            ("design", "repository-owned architectural constraints"), ("rules", "generic YAML project/framework checks")]),
        (620, 110, "Review focus", AMBER, [
            ("density", "logic/entropy signal"), ("cohesion", "multi-area change signal"),
            ("duplication", "exact normalized block reuse"), ("external", "trusted SARIF producer results")]),
        (910, 110, "Governance surfaces", RED, [
            ("doc-integrity", "links, paths, commands, references"), ("pr-metadata", "title/body/disclosure structure"),
            ("license-header", "new-file header policy"), ("jev", "optional advisory model questions")])]
    for x, y, title, fill, gates in groups:
        box(s, x, y, 250, 500, title, (), fill, LINE)
        yy = y + 55
        for gate, desc in gates:
            box(s, x+14, yy, 222, 88, gate, [desc], WHITE, LINE)
            yy += 105
    s.append(f'<text x="600" y="665" text-anchor="middle" font-size="14" font-weight="700" fill="{TEAL}">All findings share stable rule IDs, file locations, severity, and one machine-readable verdict.</text>')
    return finish("aiv-gate-catalog", s)


def trust_chain():
    s = start("From untrusted change to defensible evidence", "The policy used by the gate cannot be replaced during the run", height=520)
    stages = [
        (40, "Untrusted change", ["Git diff or strict JSON", "source workspace"]),
        (285, "Trusted policy", ["base commit", "complete .aiv tree"]),
        (530, "Frozen snapshot", ["regular Git blobs only", "no ignored extras / symlinks"]),
        (775, "Verdict", ["hard + advisory", "request + policy digest"]),
        (1020, "Audit anchor", ["OS-locked hash chain", "external head verification"]),
    ]
    for i, (x, title, lines) in enumerate(stages):
        fill = RED if i == 0 else MINT if i in (1,2) else BLUE if i == 3 else AMBER
        box(s, x, 130, 180, 120, title, lines, fill, LINE, center=True)
        if i < len(stages)-1:
            arrow(s, x+180, 190, stages[i+1][0]-5, 190)
    box(s, 165, 325, 870, 100, "Evidence binding", [
        "SHA-256(request bytes + canonical config + exact frozen sidecar bytes + resolved policy commit)",
        "Same captured inputs produce the same local verdict; external registry/tool/model dependencies are declared."
    ], "#F8FAFC", LINE, center=True)
    s.append(f'<text x="600" y="475" text-anchor="middle" font-size="14" font-weight="700" fill="{TEAL}">No time-of-check / time-of-use gap: gates and evidence consume the same frozen bytes.</text>')
    return finish("aiv-trust-evidence", s)


def aidlc():
    s = start("AIV inside an AI-DLC phase gate", "Machine-checkable integrity evidence before Construction advances", height=520)
    stages = [(40,"Agent output",["changed code","full request or file"]),
              (305,"AIV sensor",["advisory or fail closed","trusted policy ref"]),
              (570,"Evidence",["rules + locations","external inputs visible"]),
              (835,"AI-DLC gate",["record and advance","or audited intervention"])]
    for i,(x,t,lines) in enumerate(stages):
        box(s,x,135,220,120,t,lines,BLUE if i in (0,3) else MINT,LINE,center=True)
        if i<3: arrow(s,x+220,195,stages[i+1][0]-5,195)
    box(s,170,330,860,95,"Two sensor contracts",[
        "aiv: advisory file-local feedback | aiv-blocking: trusted policy required, failures and external inputs fail closed",
        "Authoritative phase evidence uses --request with the complete raw diff and changed-file set."
    ],AMBER,"#F59E0B",center=True)
    return finish("aiv-aidlc-flow",s)


def verify_flow():
    s=start("aiv verify: a stable contract for agents and tools","Strict request in, frozen-policy verdict out",height=520)
    box(s,45,125,260,250,"Request JSON",["artifact.files + rawDiff","policyRef + context","strict RFC 8259","unknown options fail closed"],BLUE,LINE)
    box(s,470,125,260,250,"Verification",["resolve policy once","snapshot immutable .aiv","run configured gates","preserve severity"],MINT,"#5EEAD4")
    box(s,895,125,260,250,"Response JSON",["pass / block","gates + findings","policy + evidence digests","external inputs + audit head"],AMBER,"#F59E0B")
    arrow(s,305,250,465,250); arrow(s,730,250,890,250)
    s.append(f'<text x="600" y="455" text-anchor="middle" font-size="14" font-weight="700" fill="{TEAL}">stdout is one JSON object; diagnostics stay on stderr; verify --capabilities provides a positive handshake.</text>')
    return finish("aiv-verify-flow",s)


def module_map():
    s = start("AIV module dependency map", "One parent plus 24 modules; the shaded CLI includes the runtime graph but not aiv-governance")
    box(s, 430, 105, 340, 72, "aiv-cli", ["composition root + shaded runnable jar"], BLUE, "#93C5FD", center=True)
    groups = [
        (40, 225, 250, 170, "Kernel", ["aiv-api", "aiv-core"]),
        (315, 225, 570, 260, "15 QualityGate plugins", ["syntax | invariant | dependency", "package-existence | test-adequacy | security", "design (+ cohesion) | density | duplication", "doc-integrity | pr-metadata | license-header", "external | rules | jev"]),
        (910, 225, 250, 170, "Adapters", ["aiv-adapter-git", "aiv-adapter-github"]),
        (40, 455, 250, 150, "Evidence", ["aiv-audit", "aiv-report", "aiv-proposal"]),
        (910, 455, 250, 150, "Standalone library", ["aiv-governance", "not bundled by CLI"]),
    ]
    for x,y,w,h,t,lines in groups:
        box(s,x,y,w,h,t,lines,MINT if t in ("Kernel","Evidence") else AMBER if "Standalone" in t else "#F8FAFC",LINE,center=True)
    for x in (165,600,1035): arrow(s,600,177,x,220)
    arrow(s,430,330,290,500); arrow(s,770,330,910,500)
    s.append(f'<text x="600" y="660" text-anchor="middle" font-size="13" font-weight="700" fill="{TEAL}">Every gate depends on aiv-api; aiv-core discovers gates with ServiceLoader; aiv-cli assembles the executable.</text>')
    return finish("aiv-module-map",s)


def runtime_flow():
    s = start("One AIV run, end to end", "The source diff and immutable policy snapshot stay separate throughout evaluation")
    stages=[("1. Parse",["strict options","select Git or JSON diff"]),
            ("2. Pin",["resolve base ref once","read config from commit OID"]),
            ("3. Freeze",["compare complete .aiv tree","materialize Git blobs / copy workspace"]),
            ("4. Evaluate",["ServiceLoader gates","severity -> baseline -> suppression"]),
            ("5. Publish",["human + JSON + SARIF","GitHub + audit + report"])]
    for i,(t,lines) in enumerate(stages):
        x=35+i*232; box(s,x,125,205,125,t,lines,[BLUE,MINT,MINT,AMBER,RED][i],LINE,center=True)
        if i<4: arrow(s,x+205,188,x+227,188)
    box(s,75,335,505,180,"Source workspace",["Changed files and full repository context", "Used by syntax, dependency discovery, test lookup,", "duplication corpus, doc link/path targets, external tools"],"#F8FAFC",LINE)
    box(s,620,335,505,180,"Policy workspace",["Frozen .aiv config sidecars from one captured source", "Used by design rules, generic rules, doc rules, baselines", "Exact bytes feed policy/evidence digest and audit commit"],MINT,"#5EEAD4")
    arrow(s,330,250,330,330); arrow(s,870,250,870,330)
    s.append(f'<text x="600" y="610" text-anchor="middle" font-size="13" font-weight="700" fill="{TEAL}">Snapshot cleanup happens after evaluation; publishers consume the already-resolved config and result.</text>')
    return finish("aiv-runtime-flow",s)


def deployment_map():
    s=start("Deployment and integration topology","Use a small profile locally, a trusted strict profile in CI, and the same verify contract for agents",height=650)
    box(s,40,115,330,135,"Developer workstation",["pre-commit / direct CLI", "minimal or balanced profile", "fast feedback before PR"],BLUE,LINE)
    box(s,435,115,330,135,"CI trust boundary",["source-built or compatible release jar", "--require-trusted-config", "JSON + SARIF + Checks + audit"],MINT,"#5EEAD4")
    box(s,830,115,330,135,"Agent / lifecycle host",["aiv verify schema v2", "MCP wrapper or AI-DLC sensor", "advisory or fail closed"],AMBER,"#F59E0B")
    arrow(s,370,183,430,183); arrow(s,765,183,825,183)
    box(s,105,350,270,130,"Repository policy",[".aiv/config.yaml", "design/doc/rule sidecars", "reviewed on the primary branch"],"#F8FAFC",LINE,center=True)
    box(s,465,350,270,130,"Evidence outputs",["terminal | JSON | SARIF", "labels | comments | Checks", "hash-chain head"],"#F8FAFC",LINE,center=True)
    box(s,825,350,270,130,"Downstream decisions",["branch protection", "OPA / policy host", "human review / AI-DLC gate"],"#F8FAFC",LINE,center=True)
    arrow(s,600,250,240,345); arrow(s,600,250,600,345); arrow(s,600,250,960,345)
    s.append(f'<text x="600" y="565" text-anchor="middle" font-size="13" font-weight="700" fill="{TEAL}">AIV computes evidence. The repository, CI platform, or orchestrator decides what must block.</text>')
    return finish("aiv-deployment-map",s)


def render_png(svg_paths):
    try:
        from svglib.svglib import svg2rlg
        from reportlab.graphics import renderPM
        for svg in svg_paths:
            drawing = svg2rlg(str(svg))
            renderPM.drawToFile(drawing, str(svg.with_suffix('.png')), fmt='PNG', dpi=150)
            print("PNG", svg.with_suffix('.png').name)
    except Exception as exc:
        print("Direct PNG rendering unavailable:", exc)
        from svglib.svglib import svg2rlg
        from reportlab.graphics import renderPDF
        import fitz
        for svg in svg_paths:
            pdf = svg.with_suffix(".visual.tmp.pdf")
            drawing = svg2rlg(str(svg))
            renderPDF.drawToFile(drawing, str(pdf))
            document = fitz.open(pdf)
            pixmap = document[0].get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
            pixmap.save(svg.with_suffix(".png"))
            document.close()
            pdf.unlink()
            print("PNG", svg.with_suffix('.png').name)


if __name__ == "__main__":
    paths = [architecture(), module_map(), runtime_flow(), deployment_map(),
             gate_catalog(), trust_chain(), aidlc(), verify_flow()]
    for path in paths:
        print("SVG", path.name)
    render_png(paths)
