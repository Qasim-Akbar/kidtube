import ast
import json
import random
from datetime import date, timedelta
from pathlib import Path

import pytest

import app.engine as engine_pkg
from app.engine import (
    AppSecondsRecord, ChildLimits, FeedResult, ItemState, PlanConfig, Projection, SecondsRecord, ViewRecord,
    build_feed, project,
)
from app.engine.feed import is_complete, is_unlimited
from app.engine.types import FeedItem, ProjectedDay, ProjectedView
from app.models.enums import OnMiss, PaceType, PlanItemStatus

CASES = 250


def random_case(seed: int) -> dict:
    rng = random.Random(seed)
    today = date(2026, 1, 1) + timedelta(days=rng.randint(0, 60))
    minutes = rng.random() < 0.3
    plan = PlanConfig(
        pace_type="minutes_per_day" if minutes else "videos_per_day",
        pace_value=rng.choice([5, 10, 15]) if minutes else rng.randint(1, 3),
        active_weekdays=rng.choice([127, 31, 127, rng.randint(1, 127)]),
        max_extra_per_day=rng.randint(0, 3),
        start_date=today - timedelta(days=rng.randint(-3, 30)),
        end_date=rng.choice([None, None, today + timedelta(days=rng.randint(-3, 20))]),
    )
    items = [
        ItemState(
            id=f"i{k}", video_id=f"v{k}", position=rng.randint(0, 5), required=rng.random() < 0.25,
            view_target=rng.choice([None, None, 1, 2, 3]), daily_cap=rng.choice([None, None, 1, 2]),
            anchor_date=rng.choice([None, None, today + timedelta(days=rng.randint(-4, 4))]),
            on_miss=rng.choice(["shift", "skip", "drop"]),
            status=rng.choice(["active"] * 8 + ["completed", "dropped"]),
            duration_seconds=rng.choice([None, 120, 300, 900]), added_on=today - timedelta(days=rng.randint(0, 20)),
        )
        for k in range(rng.randint(0, 8))
    ]
    ids = [i.id for i in items] + ["ghost"]
    views = [ViewRecord(rng.choice(ids), today + timedelta(days=rng.randint(-16, 1))) for _ in range(rng.randint(0, 25))]
    seconds = [SecondsRecord(today + timedelta(days=rng.randint(-16, 0)), rng.randint(0, 900)) for _ in range(rng.randint(0, 10))]
    app = [AppSecondsRecord(today + timedelta(days=rng.randint(-3, 0)), rng.randint(0, 1200)) for _ in range(rng.randint(0, 4))]
    limits = ChildLimits(rng.choice([None, 10, 30]), rng.choice([0, 300]))
    return dict(plan=plan, items=items, views=views, seconds=seconds, app=app, limits=limits, today=today, rng=rng)


def feed_of(c: dict, items=None, views=None) -> FeedResult:
    return build_feed(c["plan"], c["items"] if items is None else items, c["views"] if views is None else views,
                      c["seconds"], c["app"], c["limits"], c["today"])


@pytest.mark.parametrize("seed", range(CASES))
def test_feed_invariants(seed: int) -> None:
    c = random_case(seed)
    result = feed_of(c)
    by_id = {i.id: i for i in c["items"]}
    today_views = {i: sum(1 for v in c["views"] if v.item_id == i and v.local_date == c["today"]) for i in by_id}
    total_views = {i: sum(1 for v in c["views"] if v.item_id == i and v.local_date <= c["today"]) for i in by_id}

    ids = [f.item_id for f in result.items]
    assert len(ids) == len(set(ids))
    if result.status != "ok":
        assert result.items == ()
    for f in result.items:
        item = by_id[f.item_id]
        assert item.status == "active" and not is_complete(item, total_views)
        assert item.daily_cap is None or today_views[item.id] < item.daily_cap  # cap never exceeded
        assert item.anchor_date is None or item.anchor_date <= c["today"]
        assert f.required == item.required and f.video_id == item.video_id
    if result.status == "ok":
        assert len(result.items) >= 1
    locked = [f for f in result.items if f.locked]
    if locked:
        assert len(result.items) == 1 and result.items[0].required
    if result.status not in ("outside_dates", "day_off") and c["plan"].pace_type == "videos_per_day":
        assert result.remaining_videos == max(0, result.quota_today - result.views_today)
        assert len(result.items) <= result.remaining_videos
        assert result.quota_today <= c["plan"].pace_value + c["plan"].max_extra_per_day
        assert result.remaining_seconds is None
    if c["plan"].pace_type == "minutes_per_day" and result.status not in ("outside_dates", "day_off"):
        assert result.remaining_videos is None and result.quota_today <= (c["plan"].pace_value + c["plan"].max_extra_per_day) * 60
    for sid in result.skipped_item_ids + result.dropped_item_ids:
        assert by_id[sid].anchor_date < c["today"]


@pytest.mark.parametrize("seed", range(CASES))
def test_projection_invariants(seed: int) -> None:
    c = random_case(seed)
    plan = c["plan"]
    days = c["rng"].choice([0, 7, 30, 91, 200])
    p = project(plan, c["items"], c["views"], c["seconds"], c["today"], days)
    assert len(p.days) == min(days, 91)
    assert [d.date for d in p.days] == [c["today"] + timedelta(days=n) for n in range(len(p.days))]
    sim_items = [i for i in c["items"] if not is_unlimited(i)]
    assert set(p.excluded_unlimited_item_ids) == {i.id for i in c["items"] if is_unlimited(i)}

    if p.days:  # day 0 is exactly the feed (over the finite items), catch-up included
        feed = build_feed(plan, sim_items, c["views"], c["seconds"], [], ChildLimits(), c["today"])
        assert [(v.item_id, v.video_id) for v in p.days[0].views] == [(f.item_id, f.video_id) for f in feed.items]
    by_id = {i.id: i for i in c["items"]}
    for n, d in enumerate(p.days):
        if d.status != "scheduled":
            assert d.views == ()
        ids = [v.item_id for v in d.views]
        assert len(ids) == len(set(ids))
        if plan.pace_type == "videos_per_day":
            cap = plan.pace_value + plan.max_extra_per_day if n == 0 else plan.pace_value
            assert len(d.views) <= cap
        elif n > 0 and d.views:  # a video may only be started while time remains
            assert sum((by_id[i].duration_seconds or 300) for i in ids[:-1]) < plan.pace_value * 60
    if p.finish_date is not None:
        assert p.unscheduled_item_ids == ()
    assert p.remaining_finite_views >= 0


@pytest.mark.parametrize("seed", range(CASES))
def test_determinism_and_order_independence(seed: int) -> None:
    c = random_case(seed)
    rng = random.Random(seed + 10_000)
    base = feed_of(c)
    assert feed_of(c) == base
    items, views = list(c["items"]), list(c["views"])
    rng.shuffle(items)
    rng.shuffle(views)
    assert feed_of(c, items, views) == base
    p1 = project(c["plan"], c["items"], c["views"], c["seconds"], c["today"], 30)
    p2 = project(c["plan"], items, views, c["seconds"], c["today"], 30)
    assert p1 == p2 == project(c["plan"], c["items"], c["views"], c["seconds"], c["today"], 30)


@pytest.mark.parametrize("seed", range(CASES))
def test_dict_round_trips(seed: int) -> None:
    c = random_case(seed)
    assert PlanConfig.from_dict(json.loads(json.dumps(c["plan"].to_dict()))) == c["plan"]
    for i in c["items"]:
        assert ItemState.from_dict(json.loads(json.dumps(i.to_dict()))) == i
    for v in c["views"]:
        assert ViewRecord.from_dict(v.to_dict()) == v
    for s in c["seconds"]:
        assert SecondsRecord.from_dict(s.to_dict()) == s
    for s in c["app"]:
        assert AppSecondsRecord.from_dict(s.to_dict()) == s
    assert ChildLimits.from_dict(c["limits"].to_dict()) == c["limits"]
    feed = feed_of(c)
    assert FeedResult.from_dict(json.loads(json.dumps(feed.to_dict()))) == feed
    proj = project(c["plan"], c["items"], c["views"], c["seconds"], c["today"], 20)
    assert Projection.from_dict(json.loads(json.dumps(proj.to_dict()))) == proj
    assert FeedItem.from_dict(FeedItem("a", "va", True, True).to_dict()) == FeedItem("a", "va", True, True)
    assert ProjectedDay.from_dict(ProjectedDay(date(2026, 1, 1), "day_off", ()).to_dict()).status == "day_off"
    assert ProjectedView.from_dict(ProjectedView("a", "va").to_dict()) == ProjectedView("a", "va")


def test_engine_string_values_match_model_enums() -> None:
    from app.engine.types import ITEM_STATUSES, ON_MISS_VALUES, PACE_TYPES

    assert set(PACE_TYPES) == {e.value for e in PaceType}
    assert set(ON_MISS_VALUES) == {e.value for e in OnMiss}
    assert set(ITEM_STATUSES) == {e.value for e in PlanItemStatus}


def test_engine_is_pure() -> None:
    forbidden_imports = ("sqlalchemy", "app.models", "app.core", "app.api", "fastapi", "httpx", "random", "time")
    for path in Path(engine_pkg.__file__).parent.glob("*.py"):
        tree = ast.parse(path.read_text())
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom):
                names = [node.module or ""]
            for name in names:
                assert not name.startswith(forbidden_imports), f"{path.name} imports {name}"
            if isinstance(node, ast.Attribute) and node.attr in ("now", "today", "utcnow"):
                raise AssertionError(f"{path.name} reads the clock via .{node.attr}")


@pytest.mark.parametrize(
    "bad",
    [
        {"pace_type": "weekly", "pace_value": 1, "active_weekdays": 127, "max_extra_per_day": 1, "start_date": "2026-01-01", "end_date": None},
        {"pace_type": "videos_per_day", "pace_value": 1, "active_weekdays": 128, "max_extra_per_day": 1, "start_date": "2026-01-01", "end_date": None},
        {"pace_type": "videos_per_day", "pace_value": 1, "active_weekdays": 127, "max_extra_per_day": 1, "start_date": "20260101", "end_date": None},
        {"pace_type": "videos_per_day", "pace_value": True, "active_weekdays": 127, "max_extra_per_day": 1, "start_date": "2026-01-01", "end_date": None},
    ],
)
def test_from_dict_rejects_bad_plan(bad: dict) -> None:
    with pytest.raises(ValueError):
        PlanConfig.from_dict(bad)
