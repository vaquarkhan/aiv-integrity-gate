# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
"""Tests for scripts/generate-ai-review-source-appendix.py.  Run: python -m pytest scripts/test_ai_review_appendix.py"""
from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "appendix", Path(__file__).with_name("generate-ai-review-source-appendix.py"))
G = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = G  # dataclasses resolve their module through sys.modules
spec.loader.exec_module(G)


def test_committed_appendix_is_current():
    assert G.OUT.read_text(encoding="utf-8").replace("\r\n", "\n") == G.render()


def test_every_full_file_is_embedded_verbatim_and_listed_once():
    text = G.render()
    paths = [p for _, p, _ in G.FULL_FILES]
    assert len(paths) == len(set(paths))
    for p in paths:
        assert G.read(p).rstrip("\n") in text, p
        assert f"### `{p}`" in text


def test_every_excerpt_resolves_to_a_non_empty_region():
    for e in G.EXCERPTS:
        body, start, end = G.excerpt(e)
        assert body.strip(), e.path
        assert 1 <= start <= end, (e.path, start, end)


def test_fence_is_longer_than_any_backtick_run():
    assert G.fence("plain") == "```"
    assert G.fence("has ``` inside") == "````"
    assert G.fence("has ````` inside") == "``````"


def test_missing_anchor_and_missing_file_fail_loudly():
    with pytest.raises(SystemExit):
        G.excerpt(G.Excerpt("x", "pom.xml", "x", r"this anchor does not exist anywhere", None))
    with pytest.raises(SystemExit):
        G.excerpt(G.Excerpt("x", "pom.xml", "x", r"<modules>", r"this end anchor does not exist"))
    with pytest.raises(SystemExit):
        G.read("no/such/file.txt")


def test_appendix_has_no_em_or_en_dashes_outside_embedded_sources():
    text = G.render()
    prose = re.sub(r"(`{3,})[a-z]*\n.*?\n\1", "", text, flags=re.S)
    assert "\u2014" not in prose and "\u2013" not in prose
