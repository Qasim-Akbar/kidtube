"""Every list has a fully determined order: rows with IDENTICAL sort keys come back in primary-key order, every time.

Rows are inserted directly with the same timestamps / positions / dates and ids chosen so that insertion order differs
from id order. Without the id tie-break the database is free to return insertion order (SQLite) or any order (Postgres),
which would change the snapshot_version/ETag of GET /sync/pull with no real change.
"""
import uuid
from datetime import date, datetime, timezone

import pytest
from sqlalchemy.orm import Session

from app.core.clock import family_today
from app.models import Child, Device, Event, Plan, PlanItem, Video
from app.models.enums import DeviceRole, EventType, Platform, VideoStatus
from app.services.engine_input import load_engine_input, load_histories
from tests.helpers import make_family

SAME = datetime(2026, 1, 5, 9, 0, 0, 123456, tzinfo=timezone.utc)  # every row gets this timestamp
TODAY = date(2026, 1, 5)
SCRAMBLED = (0x30, 0x10, 0x40, 0x20)  # insertion order; ascending id order is 0x10, 0x20, 0x30, 0x40
REPEATS = 3


def uid(n: int) -> uuid.UUID:
    return uuid.UUID(int=n)


ASCENDING = [str(uid(n)) for n in sorted(SCRAMBLED)]


@pytest.fixture
def ties(client, engine):
    """A family whose children, videos, devices, plan items and events all tie on their sort keys."""
    headers, family_id = make_family(client)
    fam = uuid.UUID(family_id)
    with Session(engine) as s:
        for n in SCRAMBLED:
            s.add(Child(id=uid(n), family_id=fam, name=f"Kid {n:x}", created_at=SAME, updated_at=SAME))
            s.add(Video(
                id=uid(n), family_id=fam, platform=Platform.youtube, platform_video_id=f"tie{n:08x}",
                url=f"https://www.youtube.com/watch?v=tie{n:08x}", title=f"Video {n:x}", status=VideoStatus.verified,
                added_at=SAME, created_at=SAME, updated_at=SAME,
            ))
            s.add(Device(
                id=uid(n), family_id=fam, name=f"Device {n:x}", role=DeviceRole.kid, token_hash=f"hash{n:x}",
                paired_at=SAME, created_at=SAME, updated_at=SAME,
            ))
        s.flush()
        # two children with a plan whose items ALL have position 0
        for child_n in (0x10, 0x20):
            plan = Plan(id=uid(0x100 + child_n), child_id=uid(child_n), start_date=date(2026, 1, 5), created_at=SAME, updated_at=SAME)
            s.add(plan)
            s.flush()
            for n in SCRAMBLED:
                s.add(PlanItem(
                    id=uid(child_n * 0x1000 + n), plan_id=plan.id, video_id=uid(n), position=0, added_on=TODAY,
                    created_at=SAME, updated_at=SAME,
                ))
        s.flush()
        child = uid(0x10)
        for n in SCRAMBLED:  # one view per item on the same day, equal watch time, one playback error per video
            item = uid(0x10 * 0x1000 + n)
            s.add(Event(id=uuid.uuid4(), child_id=child, type=EventType.view_complete, occurred_at=SAME, local_date=TODAY,
                        payload={"plan_item_id": str(item), "watched_pct": 1.0}, plan_item_id=item, created_at=SAME))
            s.add(Event(id=uuid.uuid4(), child_id=child, type=EventType.watch_progress, occurred_at=SAME, local_date=TODAY,
                        payload={"video_id": str(uid(n)), "seconds": 30}, created_at=SAME))
            s.add(Event(id=uuid.uuid4(), child_id=child, type=EventType.playback_error, occurred_at=SAME, local_date=TODAY,
                        payload={"video_id": str(uid(n)), "code": 150}, created_at=SAME))
        s.commit()
    return headers, fam


def items_of(n_child: int) -> list[str]:
    return [str(uid(n_child * 0x1000 + n)) for n in sorted(SCRAMBLED)]


def test_children_videos_devices_lists_break_ties_by_id(client, ties) -> None:
    headers, _ = ties
    for _ in range(REPEATS):
        assert [c["id"] for c in client.get("/children", headers=headers).json()] == ASCENDING
        assert [v["id"] for v in client.get("/videos", headers=headers).json()] == ASCENDING
        assert [d["id"] for d in client.get("/devices", headers=headers).json()] == ASCENDING
        snap = client.get("/sync/pull", headers=headers).json()
        assert [c["id"] for c in snap["children"]] == ASCENDING
        assert [v["id"] for v in snap["videos"]] == ASCENDING


def test_plan_items_with_equal_position_break_ties_by_id(client, engine, ties) -> None:
    headers, fam = ties
    for _ in range(REPEATS):
        plan = client.get(f"/children/{uid(0x10)}/plan", headers=headers).json()
        assert [i["id"] for i in plan["items"]] == items_of(0x10)
        snap = client.get("/sync/pull", headers=headers).json()
        for c in snap["children"]:
            if c["plan"] is not None:
                assert [i["id"] for i in c["plan"]["items"]] == items_of(int(uuid.UUID(c["id"])))
        report = client.get(f"/reports/children/{uid(0x10)}?range=day&date=2026-01-05", headers=headers).json()
        assert [i["item_id"] for i in report["per_video"]] == items_of(0x10)
    with Session(engine) as s:
        plan = s.get(Plan, uid(0x110))
        assert [str(i.id) for i in plan.items] == items_of(0x10)  # the ORM relationship
        loaded = load_engine_input(s, s.get(Child, uid(0x10)), plan, TODAY)
        assert [i["id"] for i in loaded.items] == items_of(0x10)  # feed / projection loader


def test_history_lists_are_ordered_with_ties(client, engine, ties) -> None:
    headers, _ = ties
    for _ in range(REPEATS):
        snap = client.get("/sync/pull", headers=headers).json()
        history = next(c for c in snap["children"] if c["id"] == str(uid(0x10)))["history"]
        assert [v["plan_item_id"] for v in history["views"]] == items_of(0x10)
        assert history["watch_seconds"] == [{"local_date": "2026-01-05", "seconds": 120}]
    with Session(engine) as s:
        hist = load_histories(s, [uid(0x10), uid(0x20)], TODAY)
        assert [v[0] for v in hist[uid(0x10)].views] == [uid(0x10 * 0x1000 + n) for n in sorted(SCRAMBLED)]


def test_report_ties_break_by_id(client, ties) -> None:
    headers, _ = ties
    for _ in range(REPEATS):
        r = client.get(f"/reports/children/{uid(0x10)}?range=day&date=2026-01-05", headers=headers).json()
        # equal views and equal watch seconds for every video
        assert [(v["video_id"], v["views"], v["watch_seconds"]) for v in r["top_videos"]] == [(i, 1, 30) for i in ASCENDING]
        # equal error counts for every video
        assert [(e["video_id"], e["count"]) for e in r["playback_errors"]] == [(i, 1) for i in ASCENDING]
        assert r["totals"]["watch_seconds"] == 120 and r["totals"]["views_completed"] == 4


def test_pull_etag_is_stable_on_a_dataset_full_of_ties(client, ties) -> None:
    headers, _ = ties
    first = client.get("/sync/pull", headers=headers)
    assert first.status_code == 200
    etags = {first.headers["etag"]}
    bodies = []
    for _ in range(5):
        r = client.get("/sync/pull", headers=headers)
        etags.add(r.headers["etag"])
        bodies.append({k: v for k, v in r.json().items() if k != "server_time"})
    assert len(etags) == 1
    assert all(b == bodies[0] for b in bodies)
    again = client.get("/sync/pull", headers={**headers, "If-None-Match": first.headers["etag"]})
    assert again.status_code == 304
