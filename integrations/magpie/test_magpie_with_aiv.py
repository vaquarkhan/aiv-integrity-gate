# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Proof that the AIV config rows work with Apache Magpie's real PR triage classifier.

Runs Magpie's own `pr_management` code (config loader + triage classifier) from a local
Magpie checkout against the snippet files in `magpie-config/`. Nothing is mocked on the
Magpie side. Skipped when no checkout is found; point MAGPIE_ROOT at one to run it:

    MAGPIE_ROOT=/path/to/apache-magpie python -m pytest integrations/magpie
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
CONFIG_DIR = HERE / "magpie-config"
DEFAULT_ROOT = HERE.parents[2] / "magpie-review"
MAGPIE_ROOT = Path(os.environ.get("MAGPIE_ROOT", DEFAULT_ROOT))
PR_TOOL = MAGPIE_ROOT / "tools" / "pr-management"

pytestmark = pytest.mark.skipif(
    not (PR_TOOL / "src" / "pr_management").is_dir(),
    reason=f"no Apache Magpie checkout at {MAGPIE_ROOT} (set MAGPIE_ROOT)",
)

AIV = "AIV Integrity Gate"


@pytest.fixture(scope="module")
def magpie():
    sys.path.insert(0, str(PR_TOOL / "src"))
    from pr_management import config, model, people  # noqa: PLC0415
    from pr_management.triage import classify  # noqa: PLC0415

    spec = importlib.util.spec_from_file_location("magpie_test_helpers", PR_TOOL / "tests" / "helpers.py")
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    cfg = config.load(MAGPIE_ROOT, CONFIG_DIR)

    def decide(**pr_kwargs):
        prs = [model.from_node(helpers.pr(**pr_kwargs))]
        prs[0].commits_behind = 0
        (d,) = classify.classify(
            prs, cfg, classify.Options(viewer="triager", now=helpers.NOW, authors="default", sweeps=False),
            people.Maintainers(), {}, set(),
        )
        return d

    return {"cfg": cfg, "decide": decide, "check": helpers.check, "pr": helpers.pr}


def test_snippet_rows_are_read_by_magpie(magpie):
    cfg = magpie["cfg"]
    assert "^AIV Integrity Gate$" in cfg.real_ci_patterns
    assert AIV in cfg.static_check_patterns
    rows = [c for c in cfg.check_map if c.pattern == AIV]
    assert rows and rows[0].url and rows[0].url.startswith("https://")


def test_aiv_failure_is_a_static_check_failure_not_a_rerun(magpie):
    check = magpie["check"]
    d = magpie["decide"](rollup="FAILURE", checks=[check("Tests (unit)"), check(AIV, "FAILURE")])
    assert d.row == "12", (d.row, d.reason)
    assert d.action == "comment"
    assert AIV in d.reason


def test_aiv_failure_alongside_a_test_failure_still_routes_to_a_code_fix(magpie):
    check = magpie["check"]
    d = magpie["decide"](rollup="FAILURE", checks=[check(AIV, "FAILURE"), check("Tests (unit)", "FAILURE")])
    assert d.row == "12b", (d.row, d.reason)
    assert d.action == "comment"


def test_green_aiv_counts_as_real_ci(magpie):
    check = magpie["check"]
    d = magpie["decide"](checks=[check("Mergeable"), check(AIV)])
    assert (d.row, d.classification, d.action) == ("20", "passing", "mark-ready")


def test_without_aiv_or_tests_a_green_rollup_is_not_passing(magpie):
    check = magpie["check"]
    d = magpie["decide"](checks=[check("Mergeable"), check("boring-cyborg")])
    assert d.classification != "passing"


def test_short_fragment_would_overmatch(magpie):
    """Documents why the snippet uses the full job name, using Magpie's own matcher."""
    sys.path.insert(0, str(PR_TOOL / "src"))
    from pr_management import ci, config  # noqa: PLC0415

    loose = config.Config(static_check_patterns=["aiv"])
    assert ci.is_static("naive-tests", loose)
    assert not ci.is_static("naive-tests", magpie["cfg"])
    assert ci.is_static(AIV, magpie["cfg"])
