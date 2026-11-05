"""The JSON vectors in tests/engine_vectors are the contract with the Kotlin port."""

import json
from pathlib import Path

import pytest

from app.engine import FeedResult, Projection, run_feed, run_projection

ROOT = Path(__file__).parent / "engine_vectors"


def load(kind: str) -> list[tuple[str, dict]]:
    return [(p.stem, json.loads(p.read_text())) for p in sorted((ROOT / kind).glob("*.json"))]


FEED = load("feed")
PROJECTION = load("projection")


def test_vector_counts_meet_minimums() -> None:
    assert len(FEED) >= 40
    assert len(PROJECTION) >= 15


@pytest.mark.parametrize("kind,vectors", [("feed", FEED), ("projection", PROJECTION)])
def test_vector_files_are_well_formed(kind: str, vectors: list) -> None:
    names = [v["name"] for _, v in vectors]
    assert len(names) == len(set(names))
    for stem, v in vectors:
        assert set(v) == {"name", "input", "expected"}
        assert stem.split("_", 1)[1] == v["name"]


@pytest.mark.parametrize("stem,vector", FEED, ids=[s for s, _ in FEED])
def test_feed_vector(stem: str, vector: dict) -> None:
    assert run_feed(vector["input"]) == vector["expected"]
    assert FeedResult.from_dict(vector["expected"]).to_dict() == vector["expected"]


@pytest.mark.parametrize("stem,vector", PROJECTION, ids=[s for s, _ in PROJECTION])
def test_projection_vector(stem: str, vector: dict) -> None:
    assert run_projection(vector["input"]) == vector["expected"]
    assert Projection.from_dict(vector["expected"]).to_dict() == vector["expected"]


def test_two_children_results_are_independent() -> None:
    a = next(v for s, v in FEED if v["name"] == "two_children_a_done_for_today")
    b = next(v for s, v in FEED if v["name"] == "two_children_b_independent")
    first_a = run_feed(a["input"])
    first_b = run_feed(b["input"])
    assert run_feed(b["input"]) == first_b and run_feed(a["input"]) == first_a  # no shared state
    assert first_a["status"] == "done_today" and first_b["status"] == "ok"
    assert a["input"]["items"] == b["input"]["items"]  # same queue, different pace/views
