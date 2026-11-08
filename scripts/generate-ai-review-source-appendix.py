#!/usr/bin/env python3
# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Generate docs/AI-integration/AI-REVIEW-INTEGRATION-SOURCE.md: every line of code and test behind the opt-in
AIV + AI review integration, so another AI IDE (or a person) can recreate it exactly.

New files are embedded in full. Files that already existed are embedded as the exact changed
regions, located by anchors, so unrelated code in those files is not copied.

    python scripts/generate-ai-review-source-appendix.py           # write the appendix
    python scripts/generate-ai-review-source-appendix.py --check   # exit 1 if it is out of date
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "AI-integration" / "AI-REVIEW-INTEGRATION-SOURCE.md"

LANG = {".java": "java", ".js": "javascript", ".py": "python", ".yml": "yaml", ".yaml": "yaml",
        ".md": "markdown", ".xml": "xml", "": "text"}

# (component, path, why). Order is the build order to follow when recreating.
FULL_FILES = [
    ("A, B", "aiv-api/src/main/java/io/github/vaquarkhan/aiv/model/AIVContext.java",
     "adds EXCLUDED_CHANGED_FILES metadata key"),
    ("A, B", "aiv-plugin-invariant-template/src/main/java/io/github/vaquarkhan/aiv/plugin/invariant/InvariantGate.java",
     "rules invariant.review-instruction-change and invariant.hidden-unicode"),
    ("A, B", "aiv-plugin-invariant-template/src/test/java/io/github/vaquarkhan/aiv/plugin/invariant/InvariantGateConfigTest.java",
     "tests for both rules"),
    ("A", "aiv-core/src/test/java/io/github/vaquarkhan/aiv/core/testgates/AssertChangedFilesGate.java",
     "test gate that also asserts the excluded-files metadata"),
    ("C", "aiv-plugin-review-routing/pom.xml", "new module"),
    ("C", "aiv-plugin-review-routing/src/main/resources/META-INF/services/io.github.vaquarkhan.aiv.port.QualityGate",
     "ServiceLoader registration"),
    ("C", "aiv-plugin-review-routing/src/main/java/io/github/vaquarkhan/aiv/plugin/reviewrouting/ReviewRoutingGate.java",
     "review tier gate"),
    ("C", "aiv-plugin-review-routing/src/test/java/io/github/vaquarkhan/aiv/plugin/reviewrouting/ReviewRoutingGateTest.java",
     "gate tests"),
    ("C", "aiv-plugin-review-routing/README.md", "module README (validate-docs.py requires 50+ lines, 5+ sections)"),
    ("D", "aiv-adapter-github/src/main/java/io/github/vaquarkhan/aiv/adapter/github/GithubReviewTierLabelPublisher.java",
     "publishes aiv:review-<tier>"),
    ("D", "aiv-adapter-github/src/test/java/io/github/vaquarkhan/aiv/adapter/github/GithubReviewTierLabelPublisherTest.java",
     "fake GitHub API tests"),
    ("E", "aiv-cli/src/main/java/io/github/vaquarkhan/aiv/cli/FixBriefWriter.java", "Markdown fix brief"),
    ("E", "aiv-cli/src/test/java/io/github/vaquarkhan/aiv/cli/FixBriefWriterTest.java", "fix brief tests"),
    ("F", ".github/scripts/copilot-review/decide.js", "Copilot review decision rules"),
    ("F", ".github/scripts/copilot-review/decide.test.js", "node --test suite"),
    ("F", ".github/workflows/copilot-review.yml", "workflow"),
    ("F", ".github/skills/code-review/SKILL.md", "Copilot code review skill"),
    ("F", ".github/copilot-instructions.md", "repository instructions with the review section"),
    ("G", "integrations/magpie/README.md", "Magpie integration guide"),
    ("G", "integrations/magpie/magpie-config/pr-management-config.md", "Magpie config rows"),
    ("G", "integrations/magpie/magpie-config/pr-management-triage-ci-check-map.md", "Magpie check map row"),
    ("G", "integrations/magpie/test_magpie_with_aiv.py", "proof against Magpie's real classifier"),
    ("H", "integrations/review-ledger/aiv_review_ledger.py", "ledger tool"),
    ("H", "integrations/review-ledger/test_aiv_review_ledger.py", "ledger tests"),
    ("H", "integrations/review-ledger/README.md", "ledger guide"),
    ("H", "integrations/review-ledger/review-ledger-example.yml", "example workflow (inactive)"),
    ("J", "aiv-plugin-change-policy/pom.xml", "new module: change policy engine"),
    ("J", "aiv-plugin-change-policy/src/main/resources/META-INF/services/io.github.vaquarkhan.aiv.port.QualityGate",
     "ServiceLoader registration"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/Glob.java", "strict globs"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/DiffStats.java",
     "per-file added and removed lines"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/Classifier.java",
     "file categories and areas"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/MetaText.java",
     "title and body facts"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/GitHistory.java",
     "read-only git history"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/Facts.java",
     "fact names, rendering, JSON"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/Condition.java",
     "policy condition language"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/PolicyConfig.java",
     "pack loading, merging, validation"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/FactCollector.java",
     "fact snapshot"),
    ("J", "aiv-plugin-change-policy/src/main/java/io/github/vaquarkhan/aiv/plugin/changepolicy/ChangePolicyGate.java",
     "the gate"),
    ("J", "aiv-plugin-change-policy/src/main/resources/io/github/vaquarkhan/aiv/plugin/changepolicy/packs/oss-maintainer.yaml",
     "built-in pack"),
    ("J", "aiv-plugin-change-policy/src/main/resources/io/github/vaquarkhan/aiv/plugin/changepolicy/packs/airflow.yaml",
     "built-in pack"),
    ("J", "aiv-plugin-change-policy/src/main/resources/io/github/vaquarkhan/aiv/plugin/changepolicy/packs/strict-agent.yaml",
     "built-in pack"),
    ("J", "aiv-plugin-change-policy/src/test/java/io/github/vaquarkhan/aiv/plugin/changepolicy/TextAndPathTest.java",
     "glob, diff, classifier and text tests"),
    ("J", "aiv-plugin-change-policy/src/test/java/io/github/vaquarkhan/aiv/plugin/changepolicy/ConditionAndConfigTest.java",
     "condition and config tests"),
    ("J", "aiv-plugin-change-policy/src/test/java/io/github/vaquarkhan/aiv/plugin/changepolicy/ChangePolicyGateTest.java",
     "gate tests"),
    ("J", "aiv-plugin-change-policy/src/test/java/io/github/vaquarkhan/aiv/plugin/changepolicy/GitHistoryTest.java",
     "real git tests"),
    ("J", "aiv-plugin-change-policy/src/test/java/io/github/vaquarkhan/aiv/plugin/changepolicy/CorpusCalibrationRunner.java",
     "calibration entry point (test scope)"),
    ("J", "aiv-plugin-change-policy/README.md", "module README"),
    ("J", "benchmarks/airflow/scripts/run-change-policy-calibration.py", "calibration on the corpus"),
    ("J", "benchmarks/airflow/scripts/mine-change-patterns.py", "signal mining behind the design"),
    ("J", "benchmarks/airflow/scripts/label-pr-slop-patterns.py", "PR pattern labels over the corpus"),
    ("J", "benchmarks/airflow/scripts/mine-maintainer-pain-themes.py", "maintainer-pain theme index"),
    ("J", "scripts/e2e-change-policy.py", "end-to-end check of the gate through the real jar"),
    ("J", "docs/AI-integration/CHANGE-POLICY-ENGINE-DESIGN.md", "design, decisions, calibration"),
    ("J", "docs/AI-integration/MAINTAINER-PAIN-AND-PR-PATTERNS-ANALYSIS.md", "evidence"),
    ("K", "aiv-cli/src/main/java/io/github/vaquarkhan/aiv/cli/TriageCommand.java",
     "offline deterministic PR triage replay command"),
    ("K", "aiv-cli/src/test/java/io/github/vaquarkhan/aiv/cli/TriageCommandTest.java",
     "triage replay action, safety, schema and reproducibility tests"),
    ("K", "presets/triage/oss-maintainer.yaml", "portable starter triage policy"),
    ("K", "demos/triage/airflow-like-snapshot.json", "offline triage replay demonstration snapshot"),
    ("K", "docs/TRIAGE-PRODUCT.md", "triage product contract"),
    ("K", "docs/TRIAGE-REPLAY.md", "triage replay guide"),
    ("all", "scripts/e2e-ai-review-integration.py", "end-to-end check through the real jar"),
    ("all", "docs/pipeline-aiv-copilot.md", "user guide"),
    ("all", "docs/AI-integration/MAGPIE-AND-AIV-TOGETHER.md", "how Magpie and AIV complement each other"),
    ("all", "scripts/generate-ai-review-source-appendix.py", "this generator"),
    ("all", "scripts/test_ai_review_appendix.py", "generator tests"),
]


@dataclass
class Excerpt:
    component: str
    path: str
    why: str
    start: str           # regex; first matching line
    end: str | None      # regex; first matching line at or after start
    start_offset: int = 0
    end_inclusive: bool = True
    end_offset: int = 0


EXCERPTS = [
    Excerpt("A", "aiv-core/src/main/java/io/github/vaquarkhan/aiv/core/Orchestrator.java",
            "pass excluded files to gates (inserted right after `filteredDiff` is built)",
            r"List<ChangedFile> excluded = diff\.getChangedFiles", r"Map\.of\(AIVContext\.EXCLUDED_CHANGED_FILES"),
    Excerpt("C", "aiv-core/src/main/java/io/github/vaquarkhan/aiv/core/Orchestrator.java",
            "review-routing and change-policy are default-off", r"DEFAULT_OFF_GATES =", r'"change-policy"\);'),
    Excerpt("J", "aiv-core/src/main/java/io/github/vaquarkhan/aiv/core/Orchestrator.java",
            "rule actions honour gate-rated advisory rules inside a blocking result",
            r"for \(Finding finding : raw\.getFindings\(\)\) \{", r"String action = config\.getRuleAction"),
    Excerpt("J", "aiv-core/src/test/java/io/github/vaquarkhan/aiv/core/OrchestratorTest.java",
            "orchestrator test for gate-rated advisory rules",
            r"void gateRatedAdvisoryRulesStayAdvisoryInsideABlockingResult", r"void ruleActionCannotPromoteIntrinsicallyAdvisoryGate",
            start_offset=-1, end_inclusive=False, end_offset=-1),
    Excerpt("J", "aiv-api/src/main/java/io/github/vaquarkhan/aiv/model/GateResult.java", "advisory-rules field",
            r"Rule ids in a blocking result that the gate itself rates advisory", r"private final Set<String> advisoryRules;"),
    Excerpt("J", "aiv-api/src/main/java/io/github/vaquarkhan/aiv/model/GateResult.java",
            "five-argument constructor delegates; new six-argument constructor",
            r"public GateResult\(String gateId, boolean passed, String message, boolean blocksCi, List<Finding> findings\) \{",
            r"this\.advisoryRules = advisoryRules == null"),
    Excerpt("J", "aiv-api/src/main/java/io/github/vaquarkhan/aiv/model/GateResult.java", "getter",
            r"Rule ids the gate rates advisory inside a blocking result", r"^    \}$", end_offset=1),
    Excerpt("J", "aiv-api/src/test/java/io/github/vaquarkhan/aiv/model/GateResultTest.java", "GateResult test",
            r"void advisoryRulesAreCarriedAndDefaultEmpty", r"void threeArgConstructorSetsBlocksFromPassed",
            start_offset=-1, end_inclusive=False, end_offset=-1),
    Excerpt("J", "pom.xml", "register the change-policy module", r"<module>aiv-plugin-change-policy</module>", None),
    Excerpt("J", "pom.xml", "change-policy dependency management entry",
            r"<artifactId>aiv-plugin-change-policy</artifactId>", r"</dependency>", start_offset=-2),
    Excerpt("J", "aiv-cli/pom.xml", "CLI depends on the change-policy gate",
            r"<artifactId>aiv-plugin-change-policy</artifactId>", r"</dependency>", start_offset=-2),
    Excerpt("J", "action.yml", "PR metadata for the change-policy gate",
            r"# Read only by the opt-in change-policy gate\.", r"AIV_PR_DRAFT:"),
    Excerpt("K", "aiv-cli/src/main/java/io/github/vaquarkhan/aiv/cli/Main.java",
            "dispatch the side-effect-free triage command before normal gate parsing", r'"triage"\.equals\(args\[0\]\)', r"TriageCommand\.run"),
    Excerpt("A", "aiv-core/src/test/java/io/github/vaquarkhan/aiv/core/OrchestratorTest.java",
            "existing exclude-paths test now also asserts the excluded file reaches gates",
            r'"exclude_paths", List\.of\("\*\*/generated/\*\*"\),', r'"expected_excluded_paths"'),
    Excerpt("C", "pom.xml", "register the module", r"<module>aiv-plugin-review-routing</module>", None),
    Excerpt("C", "pom.xml", "dependency management entry",
            r"<artifactId>aiv-plugin-review-routing</artifactId>", r"</dependency>", start_offset=-2),
    Excerpt("C", "aiv-cli/pom.xml", "CLI depends on the gate",
            r"<artifactId>aiv-plugin-review-routing</artifactId>", r"</dependency>", start_offset=-2),
    Excerpt("D", "aiv-adapter-github/src/main/java/io/github/vaquarkhan/aiv/adapter/github/GithubPrLabelPublisher.java",
            "label creation takes description and color; addLabel, removeLabel, send become package-private",
            r"private static void ensureLabelExists\(", r"escapeJson\(description\)"),
    Excerpt("D", "aiv-adapter-github/src/main/java/io/github/vaquarkhan/aiv/adapter/github/GithubPrLabelPublisher.java",
            "package-private addLabel", r"^    static void addLabel\(", None),
    Excerpt("D", "aiv-adapter-github/src/main/java/io/github/vaquarkhan/aiv/adapter/github/GithubPrLabelPublisher.java",
            "package-private removeLabel", r"^    static void removeLabel\(", None),
    Excerpt("D", "aiv-adapter-github/src/main/java/io/github/vaquarkhan/aiv/adapter/github/GithubPrLabelPublisher.java",
            "package-private send", r"^    static HttpResponse<String> send\(", None),
    Excerpt("E", "aiv-adapter-github/src/main/java/io/github/vaquarkhan/aiv/adapter/github/GithubPrReviewCommentsPublisher.java",
            "new hint entries (added inside HINTS, before the existing invariant.placeholder entry and after it)",
            r'Map\.entry\("invariant\.placeholder-test",', r'"Move AI review instruction changes'),
    Excerpt("E", "aiv-adapter-github/src/main/java/io/github/vaquarkhan/aiv/adapter/github/GithubPrReviewCommentsPublisher.java",
            "hintFor becomes public", r"Fixed, deterministic remediation hint", r"public static String hintFor"),
    Excerpt("D, E", "aiv-cli/src/main/java/io/github/vaquarkhan/aiv/cli/Main.java", "import",
            r"^import io\.github\.vaquarkhan\.aiv\.adapter\.github\.GithubReviewTierLabelPublisher;", None),
    Excerpt("D, E", "aiv-cli/src/main/java/io/github/vaquarkhan/aiv/cli/Main.java", "new option variables",
            r"boolean labelReviewTier = false;", r"Path fixBriefPath = null;"),
    Excerpt("D, E", "aiv-cli/src/main/java/io/github/vaquarkhan/aiv/cli/Main.java",
            "argument parsing (inserted before the --warnings-exit-code branch)",
            r'"--label-review-tier"\.equals', r"fixBriefPath = Paths\.get"),
    Excerpt("D, E", "aiv-cli/src/main/java/io/github/vaquarkhan/aiv/cli/Main.java",
            "publication (after the SARIF writer, around the existing GitHub checks call)",
            r"if \(fixBriefPath != null\) \{", r"GithubReviewTierLabelPublisher\.publish\(", end_offset=1),
    Excerpt("D, E", "aiv-cli/src/test/java/io/github/vaquarkhan/aiv/cli/MainTest.java",
            "CLI tests for both flags", r"void outputFixBriefWritesBriefForPassingRun", r"void outputJsonWritesReport",
            start_offset=-1, end_inclusive=False, end_offset=-1),
    Excerpt("D", "aiv-cli/src/test/java/io/github/vaquarkhan/aiv/cli/MainTest.java",
            "CLI end-to-end tier label test against a fake GitHub API",
            r"void reviewRoutingGateTierIsPublishedAsALabel", r"void aiSlopLabelAndCommentPublishAgainstGithubApi",
            start_offset=-1, end_inclusive=False, end_offset=-1),
    Excerpt("D, E", "action.yml", "new inputs", r"^  label-review-tier:", r"^    default: ''"),
    Excerpt("D, E", "action.yml", "new run-step flags", r'inputs\.label-review-tier \}\}" = "true"', r"^        fi$",
            end_offset=0),
    Excerpt("D, E", "action.yml", "fix brief flag", r'BRIEF_OUT="\$\{\{ inputs\.output-fix-brief \}\}"', r"^        fi$"),
    Excerpt("A, B", "presets/airflow/config.yaml", "commented opt-in snippet (presets stay off by default)",
            r"# Optional, off by default", r"# hidden_unicode:"),
]


def lang(path: str) -> str:
    return LANG.get(Path(path).suffix, "text")


def fence(text: str) -> str:
    longest = max((len(m) for m in re.findall(r"`+", text)), default=0)
    return "`" * max(3, longest + 1)


def read(path: str) -> str:
    p = ROOT / path
    if not p.is_file():
        raise SystemExit(f"missing file: {path}")
    return p.read_text(encoding="utf-8").replace("\r\n", "\n")


def excerpt(e: Excerpt) -> tuple[str, int, int]:
    lines = read(e.path).split("\n")
    s = next((i for i, line in enumerate(lines) if re.search(e.start, line)), None)
    if s is None:
        raise SystemExit(f"anchor not found in {e.path}: {e.start}")
    end = s
    if e.end is not None:
        found = next((i for i in range(s, len(lines)) if re.search(e.end, lines[i])), None)
        if found is None:
            raise SystemExit(f"end anchor not found in {e.path}: {e.end}")
        end = found if e.end_inclusive else found - 1
    end += e.end_offset
    s += e.start_offset
    return "\n".join(lines[s:end + 1]), s + 1, end + 1


def render() -> str:
    out = [
        "# AIV and AI review integration: complete source",
        "",
        "Generated by `scripts/generate-ai-review-source-appendix.py`. Do not edit by hand; regenerate after",
        "any change and check with `--check`. Instructions for using this file are in",
        "[AI-REVIEW-INTEGRATION-HANDOFF.md](AI-REVIEW-INTEGRATION-HANDOFF.md), section 9.",
        "",
        "Part 1 lists new files in full. Part 2 lists the exact changed regions of files that already",
        "existed, with the line numbers they occupy today. Components: A instruction-file rule, B hidden",
        "Unicode rule, C review routing gate, D tier label, E fix brief, F Copilot workflow, G Magpie,",
        "H review ledger.",
        "",
        "## Contents",
        "",
    ]
    for comp, path, why in FULL_FILES:
        out.append(f"- [{comp}] `{path}`: {why}")
    out.append("- Part 2: changed regions in existing files")
    out += ["", "## Part 1: files in full", ""]
    for comp, path, why in FULL_FILES:
        text = read(path)
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]
        f = fence(text)
        out += [f"### `{path}`", "", f"Component {comp}: {why}. {text.count(chr(10))} lines, sha256 `{digest}`.", "",
                f"{f}{lang(path)}", text.rstrip("\n"), f, ""]
    out += ["## Part 2: changed regions in existing files", ""]
    for e in EXCERPTS:
        text, a, b = excerpt(e)
        f = fence(text)
        out += [f"### `{e.path}` lines {a} to {b}", "", f"Component {e.component}: {e.why}.", "",
                f"{f}{lang(e.path)}", text, f, ""]
    return "\n".join(out).rstrip("\n") + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="fail when the appendix is out of date")
    args = ap.parse_args()
    text = render()
    if args.check:
        current = OUT.read_text(encoding="utf-8").replace("\r\n", "\n") if OUT.exists() else ""
        if current != text:
            print(f"{OUT.relative_to(ROOT)} is out of date; run without --check", file=sys.stderr)
            return 1
        print("appendix up to date")
        return 0
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT.relative_to(ROOT)} ({text.count(chr(10))} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
