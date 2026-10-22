# Copyright 2026 Viquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Build the AIV Integrity Gate sales and product-value deck."""
from pathlib import Path
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.dml import MSO_THEME_COLOR

ROOT = Path(__file__).resolve().parents[2]
IMAGES = ROOT / "docs" / "images"
OUT = Path(__file__).resolve().parent / "AIV-Integrity-Gate-Sales-Deck.pptx"

NAVY = RGBColor(11, 19, 43)
TEAL = RGBColor(15, 118, 110)
TEAL2 = RGBColor(20, 184, 166)
BLUE = RGBColor(59, 130, 246)
AMBER = RGBColor(245, 158, 11)
RED = RGBColor(220, 38, 38)
SLATE = RGBColor(71, 85, 105)
PALE = RGBColor(248, 250, 252)
MINT = RGBColor(223, 247, 242)
WHITE = RGBColor(255, 255, 255)
LINE = RGBColor(203, 213, 225)

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
blank = prs.slide_layouts[6]


def rect(slide, x, y, w, h, fill=WHITE, line=LINE, radius=True):
    shape = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE if radius else MSO_SHAPE.RECTANGLE,
                                   Inches(x), Inches(y), Inches(w), Inches(h))
    shape.fill.solid(); shape.fill.fore_color.rgb = fill
    shape.line.color.rgb = line
    return shape


def text(slide, value, x, y, w, h, size=18, color=NAVY, bold=False,
         align=PP_ALIGN.LEFT, font="Aptos", valign=MSO_ANCHOR.TOP):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    frame = box.text_frame; frame.clear(); frame.word_wrap = True; frame.vertical_anchor = valign
    p = frame.paragraphs[0]; p.alignment = align
    run = p.add_run(); run.text = value; run.font.name = font; run.font.size = Pt(size)
    run.font.bold = bold; run.font.color.rgb = color
    return box


def title(slide, heading, sub=None, number=None):
    text(slide, heading, .55, .30, 12.2, .55, 28, NAVY, True)
    if sub: text(slide, sub, .58, .88, 12.0, .40, 13, SLATE)
    if number is not None: text(slide, f"{number:02d}", 12.45, .35, .35, .25, 9, TEAL, True, PP_ALIGN.RIGHT)
    line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(.58), Inches(1.24), Inches(.62), Inches(.04))
    line.fill.solid(); line.fill.fore_color.rgb = TEAL2; line.line.fill.background()


def footer(slide):
    text(slide, "AIV Integrity Gate  |  deterministic evidence for agent-generated change", .55, 7.17, 8.8, .18, 8, SLATE)
    text(slide, "Viquar Khan", 10.8, 7.17, 1.9, .18, 8, SLATE, False, PP_ALIGN.RIGHT)


def bullets(slide, items, x, y, w, h, size=18, color=NAVY, gap=7):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame; tf.clear(); tf.word_wrap = True
    for i, item in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.text = item; p.level = 0; p.font.name = "Aptos"; p.font.size = Pt(size); p.font.color.rgb = color
        p.space_after = Pt(gap); p.text = "•  " + item
    return box


def card(slide, heading, body, x, y, w, h, accent=TEAL, fill=PALE):
    rect(slide, x, y, w, h, fill, LINE)
    bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(.07), Inches(h))
    bar.fill.solid(); bar.fill.fore_color.rgb = accent; bar.line.fill.background()
    text(slide, heading, x+.22, y+.16, w-.38, .32, 15, NAVY, True)
    text(slide, body, x+.22, y+.55, w-.38, h-.68, 11.5, SLATE)


def image_slide(heading, subtitle, filename, num, top=1.48, height=5.35):
    slide = prs.slides.add_slide(blank); title(slide, heading, subtitle, num)
    slide.shapes.add_picture(str(IMAGES / filename), Inches(.55), Inches(top), width=Inches(12.2), height=Inches(height))
    footer(slide); return slide


def gate_group(heading, subtitle, entries, num, fill):
    slide = prs.slides.add_slide(blank); title(slide, heading, subtitle, num)
    positions = [(.65,1.55),(6.75,1.55),(.65,4.12),(6.75,4.12)]
    for (name, job, detail, mode), (x,y) in zip(entries, positions):
        rect(slide,x,y,5.9,2.15,fill,LINE)
        text(slide,name,x+.25,y+.20,3.8,.34,18,NAVY,True)
        badge = rect(slide,x+4.45,y+.16,1.15,.34,WHITE,LINE)
        text(slide,mode,x+4.48,y+.21,1.08,.20,9,TEAL,True,PP_ALIGN.CENTER)
        text(slide,job,x+.25,y+.67,5.35,.35,13,TEAL,True)
        text(slide,detail,x+.25,y+1.08,5.35,.86,11.5,SLATE)
    footer(slide); return slide


# 1 problem
s=prs.slides.add_slide(blank)
rect(s,0,0,13.333,7.5,NAVY,NAVY,False)
text(s,"AI agents create change faster",.75,.72,11.8,.72,34,WHITE,True)
text(s,"than teams can verify it.",.75,1.48,11.8,.72,34,TEAL2,True)
text(s,"The bottleneck moved from generation to delivery, review, and trust.",.78,2.43,11.5,.46,19,RGBColor(203,213,225))
for i,(h,b) in enumerate([("More output","Large polished changes arrive quickly."),("Finite attention","Maintainers still prove syntax, tests, dependencies, secrets, and design."),("Weak evidence","A passing conversation is not a reproducible delivery decision.")]):
    x=.75+i*4.15; rect(s,x,3.45,3.75,1.65,RGBColor(20,31,61),RGBColor(51,65,85))
    text(s,h,x+.22,3.70,3.3,.34,17,TEAL2,True)
    text(s,b,x+.22,4.18,3.3,.62,12,WHITE)
text(s,"Problem: every pull request asks humans to rediscover facts a machine can establish first.",.85,5.72,11.55,.55,19,WHITE,True,PP_ALIGN.CENTER)
text(s,"AIV Integrity Gate  |  Viquar Khan",.8,6.75,11.7,.28,10,RGBColor(148,163,184),False,PP_ALIGN.CENTER)

# 2 existing solutions
s=prs.slides.add_slide(blank); title(s,"Why existing solutions do not close the gap","Each tool remains valuable, but each owns a different layer",2)
items=[("Compiler / type checker","Proves one build/type configuration, often late in CI."),("SAST / secret scanner","Deep specialist analysis, usually separate reports and policy."),("LLM reviewer","Contextual judgement, but non-deterministic and unsuitable as the only hard gate."),("SDLC orchestrator","Sequences agents and humans; does not compute every code fact."),("Policy engine","Decides allow/deny from facts; does not derive code evidence by itself."),("Human review","Final judgement, but the scarcest and most expensive verification resource.")]
for i,(h,b) in enumerate(items):
    x=.65+(i%3)*4.15; y=1.55+(i//3)*2.30; card(s,h,b,x,y,3.85,1.78,[BLUE,AMBER,RED,TEAL2,BLUE,AMBER][i])
text(s,"Missing layer: one protected, diff-scoped evidence receipt that composes these tools.",.8,6.35,11.7,.38,17,TEAL,True,PP_ALIGN.CENTER); footer(s)

# 3 solution
s=prs.slides.add_slide(blank); title(s,"AIV turns untrusted change into verifiable evidence","One pinned policy, one immutable snapshot, one structured verdict",3)
for i,(h,b) in enumerate([("Check facts","Parse, imports, package names, assertion evidence, secrets, design, and project rules."),("Protect the yardstick","Pin one policy commit; reject drift, ignored extras, symlinks, and path escapes."),("Preserve the decision","Bind request, config, sidecars, commit, findings, severity, and audit head."),("Compose, do not replace","Fold trusted SARIF in; send judgement-heavy questions to advisory model/human review.")]):
    x=.65+(i%2)*6.1; y=1.55+(i//2)*2.20; card(s,h,b,x,y,5.8,1.72,[BLUE,TEAL2,AMBER,RED][i])
text(s,"AIV does not identify authorship. It asks whether the change is backed by objective evidence.",.8,6.25,11.7,.48,18,TEAL,True,PP_ALIGN.CENTER); footer(s)

# 4 architecture
image_slide("What is AIV? Complete product architecture","Five entry points, one trust boundary, sixteen gates, five evidence outputs","aiv-architecture.png",4,1.42,5.55)

# 5 gates and use guidance
image_slide("What gates exist, and where should each be used?","Hard evidence first; structural review signals advisory; governance surfaces opt-in","aiv-gate-catalog.png",5,1.42,5.55)

# 6 use cases
s=prs.slides.add_slide(blank); title(s,"Where teams use AIV","One engine across local development, CI, agents, lifecycle, and governance",6)
usecases=[("Before commit","Catch parse failures, conflict/paste artifacts, assertion-free tests, and credential forms."),("Pull-request CI","Require trusted policy; publish JSON, SARIF, Checks, comments, labels, and audit."),("Coding agents / MCP hosts","Send strict artifact JSON to aiv verify and consume schema-v2 verdict evidence."),("AI-DLC phase boundaries","Run advisory file feedback or fail-closed full-request sensors."),("Architecture policy","Enforce design constraints and generic YAML project/framework rules."),("Audit and calibration","Verify hash-chain heads, generate HTML/JSONL, and use reviewer labels to tune severity.")]
for i,(h,b) in enumerate(usecases):
    x=.65+(i%3)*4.15; y=1.55+(i//3)*2.30; card(s,h,b,x,y,3.85,1.78,[BLUE,TEAL2,AMBER,RED,TEAL,BLUE][i])
footer(s)

# 7-10 gate groups
change=[("syntax","Can the named parser read it?","Strict RFC JSON and bounded Java/Python/JS/TS/Go validation. Unavailable parsers are visible and optionally blocking.","hard / unverified"),
("invariant","Did edit artifacts enter source?","Conflict blocks, placeholders, elision lines, assistant chatter, provenance text, and tautology tests on added lines.","hard"),
("dependency","Are imports backed locally?","Java and Python roots are compared with manifests, standard libraries, first-party packages, and reviewed allow entries.","hard"),
("package-existence","Does the declared package exist?","New PyPI, npm, and Maven declarations are classified as found, absent, or unverified with bounded registry calls.","hard / external")]
gate_group("Gate family 1: change integrity","Stop guaranteed breakage and invented dependencies first",change,7,MINT)
behavior=[("test-adequacy","Do added tests contain assertion evidence?","Assertion-free test functions block. Production changes without a conventional test remain an advisory review signal.","hard + advisory"),
("security","Did credential material enter added lines?","High-confidence vendor formats, private keys, assigned secrets, and keyword-bound entropy with project allow rules.","hard, opt-in"),
("design","Does the change follow repository constraints?","Frozen YAML design rules enforce forbidden and required calls without letting the change edit its own policy.","hard"),
("rules","Can the team express a new check in YAML?","Generic pattern, call-argument, and hollow-function rules; project packs stay under protected .aiv policy.","configurable")]
gate_group("Gate family 2: behavioral evidence","Demand proof around tests, security, and design",behavior,8,RGBColor(232,241,255))
review=[("density","Where is structure unusually thin?","Java logic density and configured entropy identify review targets; never used alone as proof of bad code.","advisory"),
("cohesion","Is the change spread across many areas?","Path-area and file-count signals focus architecture review without blocking legitimate broad changes.","advisory"),
("duplication","Was an existing code block copied?","Exact normalized windows compare added code with bounded workspace sources and name the existing location.","advisory"),
("external","What do trusted analyzers already know?","Run configured tools, ingest SARIF, filter to changed files, preserve severity, and expose missing/timeout states.","configurable")]
gate_group("Gate family 3: review focus","Move scarce human attention to the riskiest parts",review,9,RGBColor(255,244,214))
gov=[("doc-integrity","Do instructions point to real things?","Local links, paths, cross-references, required terms, and complete commands across changed documentation.","opt-in"),
("pr-metadata","Did the change bring review context?","Title, body, untouched template, and optional AI-assistance disclosure structure.","opt-in"),
("license-header","Do new files carry approved markers?","Project-defined headers on newly added source; generated, blank, excluded, and modified files are scoped out.","opt-in"),
("jev","Which calibrated questions need model judgement?","Optional yes/no decision-model questions are always advisory and declared as an external input.","advisory, opt-in")]
gate_group("Gate family 4: governance surfaces","Make documentation, review metadata, and policy visible",gov,10,RGBColor(254,236,236))

# 12 trust
image_slide("The gate cannot be edited from inside the change","One pinned commit, exact Git blobs, one immutable policy snapshot","aiv-trust-evidence.png",11,1.50,5.15)

# 13 verify
image_slide("A stable contract for agents and tools","Strict JSON in; policy-bound verdict JSON out","aiv-verify-flow.png",12,1.50,5.15)

# 14 aidlc
image_slide("Native fit for AI-driven lifecycle gates","Advisory feedback or fail-closed evidence at an AI-DLC boundary","aiv-aidlc-flow.png",13,1.50,5.15)

# 15 integration matrix
s=prs.slides.add_slide(blank); title(s,"Integrate at the moment a change is produced","Use the same verdict across local, CI, agent, and lifecycle paths",14)
rows=[("pre-commit","staged diff","human report + exit"),("GitHub Action","pull-request diff","JSON, SARIF, Checks, labels, comments"),("aiv verify","strict JSON artifact","schema v2 verdict + evidence digest"),("AI-DLC sensor","file or full request","pass + violations, advisory or fail closed"),("OPA / policy host","verdict facts","allow / deny / route decision")]
for i,(a,b,c) in enumerate(rows):
    y=1.55+i*.95; rect(s,.7,y,11.95,.72,WHITE,LINE)
    text(s,a,.92,y+.18,2.3,.26,14,NAVY,True); text(s,b,3.35,y+.18,3.2,.26,13,SLATE); text(s,c,6.65,y+.18,5.5,.26,13,TEAL,True)
text(s,"Front door",.92,1.30,2,.2,10,SLATE,True); text(s,"Input",3.35,1.30,2,.2,10,SLATE,True); text(s,"Output",6.65,1.30,2,.2,10,SLATE,True); footer(s)

# 16 evidence
s=prs.slides.add_slide(blank); title(s,"Evidence survives the CI run","Verdicts become queryable, calibratable, and externally verifiable",15)
card(s,"Locked append","Thread and inter-process locking serialize each hash-chain append.",.7,1.55,3.8,1.65,TEAL2)
card(s,"External anchor","Store the returned chain head outside the log to detect complete rewrites.",4.78,1.55,3.8,1.65,AMBER)
card(s,"Evidence report","Generate static HTML plus JSONL for DuckDB and governance analytics.",8.86,1.55,3.8,1.65,BLUE)
card(s,"Rule calibration","Join reviewer labels by stable rule/file/line and promote only with recorded evidence.",.7,3.65,3.8,1.65,TEAL)
card(s,"Proposal integrity","Apply template-driven checks to files, wiki pages, or issues; shipped KIP/FLIP/SPIP templates are examples.",4.78,3.65,3.8,1.65,RED)
card(s,"Pinned provenance","Audit base_sha and policy_commit name the exact commit that supplied policy.",8.86,3.65,3.8,1.65,NAVY)
footer(s)

# 17 proof
s=prs.slides.add_slide(blank); title(s,"What is verified today","Product claims are tied to executable evidence",16)
for i,(n,l) in enumerate([("25","reactor projects"),("844","tests discovered"),("0","test failures / errors"),("0","critical / high review findings")]):
    x=.65+i*3.1; rect(s,x,1.60,2.8,1.35,PALE,LINE); text(s,n,x+.18,1.83,.9,.5,29,TEAL,True); text(s,l,x+1.0,1.91,1.55,.52,11,NAVY,True)
bullets(s,["Full Maven clean verify succeeded; all configured coverage gates passed.","AI-DLC adapter: 8 unit tests passed.","24 module READMEs and all local documentation links validated.","Generated SVG and PNG product assets validated.","Seven adversarial hardening loops ended with independent READY certification: Critical 0, High 0."],.85,3.45,11.4,2.5,16)
text(s,"One OS capability-gated symlink test is skipped where Windows cannot create symbolic links; containment is enforced in code and reviewed statically.",.9,6.35,11.4,.35,10,SLATE,False,PP_ALIGN.CENTER); footer(s)

# 18 deployment
s=prs.slides.add_slide(blank); title(s,"Adopt in four controlled steps","Start advisory, prove fit, then enforce the objective checks",17)
steps=[("1","Observe","Run doctor/advisory and collect findings."),("2","Calibrate","Review rule fit and configure approved paths/thresholds."),("3","Protect","Pin base policy, enable strict toolchains, require trusted config."),("4","Enforce","Make objective gates required; retain advisory review signals.")]
for i,(n,h,b) in enumerate(steps):
    x=.65+i*3.1; rect(s,x,1.75,2.8,3.35,[RGBColor(232,241,255),MINT,RGBColor(255,244,214),RGBColor(254,236,236)][i],LINE)
    text(s,n,x+.22,1.98,.55,.55,28,TEAL,True); text(s,h,x+.85,2.08,1.7,.35,17,NAVY,True); text(s,b,x+.25,2.85,2.3,1.25,13,SLATE)
text(s,"Success criterion: less reviewer time spent proving basic facts, with no hidden policy fallback or silent unverified state.",.9,5.85,11.4,.65,17,TEAL,True,PP_ALIGN.CENTER); footer(s)

# 19 developer experience
s=prs.slides.add_slide(blank); title(s,"Designed for developers, not a governance portal","One jar, repository-owned policy, outputs your tools already consume",18)
left=["Bootstrap: aiv init --workspace .","Run: aiv --diff origin/main --require-trusted-config","Agent: aiv verify --policy-ref origin/main --require-trusted-policy","Explain: aiv explain <gate>","Audit: aiv audit verify <log> --expect-head <hash>"]
rect(s,.7,1.55,6.0,4.75,NAVY,NAVY); text(s,"CLI workflow",1.0,1.85,2.8,.4,18,TEAL2,True)
for i,line in enumerate(left): text(s,"$ "+line,1.0,2.45+i*.68,5.3,.42,12,WHITE,False,font="Consolas")
card(s,"Repository-owned","Configuration and rule packs live under protected .aiv policy.",7.05,1.55,5.55,1.3,TEAL2)
card(s,"Explainable","Stable IDs, file/line, severity, message, and offline gate help.",7.05,3.05,5.55,1.3,BLUE)
card(s,"Composable","JSON, SARIF, GitHub, AI-DLC, external analyzers, and policy hosts.",7.05,4.55,5.55,1.3,AMBER)
footer(s)

# 20 differentiation
s=prs.slides.add_slide(blank); title(s,"Where AIV fits","Complement the tools you already trust",19)
rows=[("Compiler / type checker","whole-program correctness","AIV catches diff integrity first; external gate folds results into one receipt"),("SAST / secret scanner","deep repository analysis","AIV scopes added-line evidence and preserves their SARIF"),("LLM reviewer","contextual judgement","AIV supplies the reproducible facts; model advice stays advisory"),("SDLC orchestrator","sequence and human routing","AIV is the deterministic evidence step at each boundary"),("Policy engine","allow/deny logic","AIV computes code facts the policy engine cannot derive alone")]
for i,(tool,job,fit) in enumerate(rows):
    y=1.5+i*1.02; rect(s,.65,y,12.0,.8,WHITE,LINE)
    text(s,tool,.88,y+.16,2.5,.42,13,NAVY,True); text(s,job,3.45,y+.16,2.65,.42,12,SLATE); text(s,fit,6.25,y+.16,6.05,.45,12,TEAL,True)
footer(s)

# 21 call to action
s=prs.slides.add_slide(blank); title(s,"Pilot one repository","Prove the gate in a real review flow before expanding",20)
text(s,"30-day pilot outcome",.7,1.55,4.0,.45,22,NAVY,True)
bullets(s,["Baseline the current findings in advisory mode.","Select objective hard gates and repository rule packs.","Enable trusted base-ref policy and machine reports.","Measure findings acted on, suppressions, and review feedback.","Decide expand, tune, or stop based on evidence."],.8,2.15,5.4,3.3,16)
rect(s,6.65,1.55,5.95,4.55,MINT,TEAL2)
text(s,"The ask",6.95,1.90,2.0,.4,20,TEAL,True)
text(s,"Give AIV one repository, one workflow, and one maintainer feedback loop.",6.95,2.55,5.25,1.0,23,NAVY,True)
text(s,"The product is ready for a controlled pilot. The evidence will decide the next investment.",6.95,4.10,5.10,.75,15,SLATE)
footer(s)

# 22 close
s=prs.slides.add_slide(blank)
rect(s,0,0,13.333,7.5,NAVY,NAVY,False)
text(s,"Ship more agent-generated change.",.8,1.25,11.7,.65,32,WHITE,True,PP_ALIGN.CENTER)
text(s,"Spend less human attention proving basic facts.",.8,2.05,11.7,.65,30,TEAL2,True,PP_ALIGN.CENTER)
text(s,"AIV Integrity Gate",.8,3.35,11.7,.55,25,WHITE,True,PP_ALIGN.CENTER)
text(s,"Deterministic evidence. Frozen policy. Defensible verdict.",.8,4.05,11.7,.45,18,RGBColor(203,213,225),False,PP_ALIGN.CENTER)
rect(s,4.05,5.10,5.25,.72,RGBColor(20,31,61),RGBColor(51,65,85))
text(s,"github.com/vaquarkhan/aiv-integrity-gate",4.15,5.34,5.05,.25,13,WHITE,True,PP_ALIGN.CENTER)
text(s,"Viquar Khan  |  vaquar.khan@gmail.com",.8,6.55,11.7,.3,12,RGBColor(148,163,184),False,PP_ALIGN.CENTER)

for slide in prs.slides:
    slide.background.fill.solid()
    if slide.background.fill.fore_color.type is None:
        slide.background.fill.fore_color.rgb = WHITE

prs.core_properties.title = "AIV Integrity Gate: Deterministic Evidence for Agent-Generated Change"
prs.core_properties.subject = "Sales and product value deck"
prs.core_properties.author = "Viquar Khan"
prs.core_properties.keywords = "AIV, AI-DLC, software integrity, deterministic gate, MCP, SARIF"
prs.core_properties.comments = "Claims grounded in the validated local source tree and adversarial product review."
prs.save(OUT)
print(f"WROTE {OUT} ({len(prs.slides)} slides)")
