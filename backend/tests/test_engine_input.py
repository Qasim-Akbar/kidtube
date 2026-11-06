"""Unit tests for the DB -> engine loader (app/services/engine_input.py), without HTTP."""

import uuid
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import Engine, event, inspect
from sqlalchemy.orm import Session

from app.engine import run_feed
from app.models import Child, Event, Family, Plan, PlanItem, Video
from app.models.enums import EventType, PaceType, Platform, VideoStatus
from app.services.engine_input import load_engine_input

TODAY = date(2026, 1, 7)
NOW = datetime(2026, 1, 7, 10, 0, tzinfo=timezone.utc)


def build(session: Session, n_items: int = 3, video_status: dict[int, VideoStatus] | None = None):
    family = Family(name="F")
    child = Child(family=family, name="Ali", daily_app_minutes=30)
    session.add_all([family, child])
    session.flush()
    plan = Plan(child_id=child.id, start_date=date(2026, 1, 1), pace_value=2)
    session.add(plan)
    session.flush()
    items = []
    for n in range(n_items):
        video = Video(
            family_id=family.id, platform=Platform.youtube, platform_video_id=f"vid{n:08d}", url="https://x/y",
            status=(video_status or {}).get(n, VideoStatus.verified), duration_seconds=120 + n,
        )
        session.add(video)
        session.flush()
        item = PlanItem(plan_id=plan.id, video_id=video.id, position=n, added_on=date(2026, 1, 1), view_target=2)
        session.add(item)
        items.append(item)
    session.flush()
    return family, child, plan, items


def add_event(session: Session, child: Child, type: EventType, local_date: date, payload: dict, plan_item_id=None) -> None:
    session.add(Event(
        id=uuid.uuid4(), child_id=child.id, type=type, occurred_at=NOW, local_date=local_date, payload=payload,
        plan_item_id=plan_item_id,
    ))


def view(session, child, item, d: date) -> None:
    add_event(session, child, EventType.view_complete, d, {"plan_item_id": str(item.id), "watched_pct": 1}, item.id)


def test_views_are_aggregated_and_expanded_per_view(session: Session) -> None:
    _, child, plan, items = build(session)
    for d in (date(2026, 1, 5), date(2026, 1, 5), date(2026, 1, 5), date(2026, 1, 6), date(2026, 1, 6)):
        view(session, child, items[0], d)
    view(session, child, items[1], date(2026, 1, 7))
    session.commit()
    loaded = load_engine_input(session, child, plan, TODAY)
    key = lambda v: (v["item_id"], v["local_date"])  # noqa: E731
    assert sorted(loaded.views, key=key) == sorted(
        [{"item_id": str(items[0].id), "local_date": "2026-01-05"}] * 3
        + [{"item_id": str(items[0].id), "local_date": "2026-01-06"}] * 2
        + [{"item_id": str(items[1].id), "local_date": "2026-01-07"}], key=key)


def test_future_foreign_and_deleted_item_views_are_not_loaded(session: Session) -> None:
    _, child, plan, items = build(session)
    other_child = Child(family_id=child.family_id, name="Sara")
    session.add(other_child)
    session.flush()
    view(session, child, items[0], date(2026, 1, 8))  # after today
    view(session, other_child, items[0], date(2026, 1, 6))  # another child's event
    ghost = PlanItem(id=uuid.uuid4(), position=9, added_on=TODAY)
    add_event(session, child, EventType.view_complete, TODAY, {}, plan_item_id=ghost.id)  # not an item of this plan
    add_event(session, child, EventType.view_complete, TODAY, {"watched_pct": 1}, plan_item_id=None)
    session.commit()
    assert load_engine_input(session, child, plan, TODAY).views == []


def count_statements(engine: Engine, fn) -> list[str]:
    statements: list[str] = []

    def record(conn, cursor, statement, *args):
        statements.append(statement)

    event.listen(engine, "before_cursor_execute", record)
    try:
        fn()
    finally:
        event.remove(engine, "before_cursor_execute", record)
    return statements


@pytest.mark.parametrize("n_items", [2, 25])
def test_views_come_from_one_grouped_query_regardless_of_item_count(session: Session, engine: Engine, n_items: int) -> None:
    _, child, plan, items = build(session, n_items)
    for item in items:
        for d in (date(2026, 1, 5), date(2026, 1, 6)):
            view(session, child, item, d)
    session.commit()
    session.refresh(plan)
    session.refresh(child)  # so the count below is the loader's own queries only
    statements = count_statements(engine, lambda: load_engine_input(session, child, plan, TODAY))
    grouped = [s for s in statements if "GROUP BY" in s and "plan_item_id" in s]
    assert len(grouped) == 1
    assert len(statements) == 4  # items join + the 3 history queries (views, watch+extension seconds, app sessions)


def test_statement_count_does_not_grow_with_items(session: Session, engine: Engine) -> None:
    counts = []
    for n in (1, 30):
        session.rollback()
        _, child, plan, items = build(session, n)
        for item in items:
            view(session, child, item, date(2026, 1, 6))
        session.commit()
        session.refresh(plan)
        session.refresh(child)
        counts.append(len(count_statements(engine, lambda: load_engine_input(session, child, plan, TODAY))))
    assert counts[0] == counts[1]


def test_supporting_indexes_exist(engine: Engine) -> None:
    names = {i["name"] for i in inspect(engine).get_indexes("events")}
    assert {"ix_events_child_id_type_plan_item_id_local_date", "ix_events_child_id_type_local_date"} <= names
    # the view aggregation filters on child_id, type and groups by plan_item_id, local_date: the index covers it in order
    cols = next(i["column_names"] for i in inspect(engine).get_indexes("events")
                if i["name"] == "ix_events_child_id_type_plan_item_id_local_date")
    assert cols == ["child_id", "type", "plan_item_id", "local_date"]


def test_watch_seconds_window_app_time_and_limits(session: Session) -> None:
    _, child, plan, items = build(session)
    wp = lambda d, s: add_event(session, child, EventType.watch_progress, d, {"video_id": str(uuid.uuid4()), "seconds": s})  # noqa: E731
    wp(TODAY, 10.4)
    wp(TODAY, 20.4)
    wp(date(2026, 1, 3), 100)
    wp(TODAY - timedelta(days=14), 7)  # inside the 14-day catch-up window
    wp(TODAY - timedelta(days=15), 999)  # older than the engine can ever use
    add_event(session, child, EventType.app_session, TODAY, {"start": "2026-01-07T09:00:00+00:00", "end": "2026-01-07T09:10:00+00:00"})
    add_event(session, child, EventType.app_session, TODAY, {"start": "2026-01-07T09:20:00+00:00", "end": "2026-01-07T09:25:30+00:00"})
    add_event(session, child, EventType.app_session, date(2026, 1, 6), {"start": "2026-01-06T09:00:00+00:00", "end": "2026-01-06T10:00:00+00:00"})
    add_event(session, child, EventType.time_extension, TODAY, {"seconds": 900})
    add_event(session, child, EventType.time_extension, TODAY, {"seconds": 300})
    add_event(session, child, EventType.time_extension, date(2026, 1, 6), {"seconds": 5000})
    session.commit()
    loaded = load_engine_input(session, child, plan, TODAY)
    assert loaded.seconds == [{"local_date": "2025-12-24", "seconds": 7}, {"local_date": "2026-01-03", "seconds": 100},
                              {"local_date": "2026-01-07", "seconds": 31}]
    assert loaded.app_seconds == [{"local_date": "2026-01-07", "seconds": 930}]
    assert loaded.limits == {"daily_app_minutes": 30, "app_time_extra_seconds": 1200}


def test_no_events_gives_empty_lists(session: Session) -> None:
    _, child, plan, _ = build(session)
    loaded = load_engine_input(session, child, plan, TODAY)
    assert (loaded.views, loaded.seconds, loaded.app_seconds) == ([], [], [])
    assert loaded.limits == {"daily_app_minutes": 30, "app_time_extra_seconds": 0}


def test_unavailable_active_items_are_excluded_but_completed_ones_pass_through(session: Session) -> None:
    _, child, plan, items = build(session, 3, {0: VideoStatus.unavailable, 1: VideoStatus.unavailable})
    items[1].status = __import__("app.models.enums", fromlist=["PlanItemStatus"]).PlanItemStatus.completed
    session.commit()
    loaded = load_engine_input(session, child, plan, TODAY)
    assert loaded.unavailable_item_ids == [str(items[0].id)]
    assert [i["id"] for i in loaded.items] == [str(items[1].id), str(items[2].id)]
    assert set(loaded.videos) == {str(i.video_id) for i in items}  # all videos kept for enrichment


def test_loaded_input_is_valid_engine_input_and_json_safe(session: Session) -> None:
    import json

    _, child, plan, items = build(session)
    view(session, child, items[0], TODAY)
    session.commit()
    loaded = load_engine_input(session, child, plan, TODAY)
    data = json.loads(json.dumps(loaded.feed_input()))
    result = run_feed(data)
    assert result["status"] == "ok" and result["views_today"] == 1
    assert loaded.items[0]["duration_seconds"] == 120 and loaded.items[0]["added_on"] == "2026-01-01"
    assert loaded.plan["pace_type"] == PaceType.videos_per_day.value
    assert json.loads(json.dumps(loaded.projection_input(10)))["days"] == 10
