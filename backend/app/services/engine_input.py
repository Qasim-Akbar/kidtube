"""Loads one child's data from the DB into the JSON shape the pure engine consumes (app.engine.run_feed/run_projection).

Kept separate from the endpoints so it can be unit-tested. The event-derived inputs (views, watch seconds, app seconds,
time extensions) come from ONE function, `load_histories`, which also feeds the offline history in GET /sync/pull.
It runs three grouped queries for any number of children and never one per item, day or event.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.engine.feed import CATCH_UP_WINDOW_DAYS
from app.engine.types import ChildLimits, ItemState, PlanConfig
from app.models import Child, Event, Plan, PlanItem, Video
from app.models.enums import EventType, PlanItemStatus, VideoStatus


@dataclass(frozen=True)
class EngineInput:
    today: date
    plan: dict[str, Any]
    items: list[dict[str, Any]]
    views: list[dict[str, Any]]
    seconds: list[dict[str, Any]]
    app_seconds: list[dict[str, Any]]
    limits: dict[str, Any]
    unavailable_item_ids: list[str]
    videos: dict[str, Video]  # by str(video.id), for response enrichment

    def feed_input(self) -> dict[str, Any]:
        return {
            "plan": self.plan, "items": self.items, "views": self.views, "seconds": self.seconds,
            "app_seconds": self.app_seconds, "limits": self.limits, "today": self.today.isoformat(),
        }

    def projection_input(self, days: int) -> dict[str, Any]:
        return {
            "plan": self.plan, "items": self.items, "views": self.views, "seconds": self.seconds,
            "today": self.today.isoformat(), "days": days,
        }


def plan_config(plan: Plan) -> PlanConfig:
    return PlanConfig(
        pace_type=plan.pace_type.value, pace_value=plan.pace_value, active_weekdays=plan.active_weekdays,
        max_extra_per_day=plan.max_extra_per_day, start_date=plan.start_date, end_date=plan.end_date,
    )


HISTORY_DAYS = 30  # family-local days of watch / app / extension seconds in a history (today and the 29 before it)


@dataclass(frozen=True)
class ChildHistory:
    """One child's event-derived numbers, all by the child's local date (events.local_date), never UTC timestamps."""

    views: list[tuple[uuid.UUID, date, int]]  # (plan_item_id, local_date, count), all-time, only items still in the plan
    watch_seconds: list[tuple[date, int]]  # last HISTORY_DAYS days
    app_seconds: list[tuple[date, int]]
    time_extension_seconds: list[tuple[date, int]]


def load_histories(
    db: Session, child_ids: Sequence[uuid.UUID], today: date, days: int = HISTORY_DAYS
) -> dict[uuid.UUID, ChildHistory]:
    """History for several children with exactly three queries (views, watch + extension seconds, app sessions).

    Views are all-time up to `today`, counted only for events whose plan item belongs to THAT child's plan (a deleted
    item, or an event pointing at another child's item, is not counted). Seconds cover [today - days + 1, today].
    """
    out = {cid: ChildHistory([], [], [], []) for cid in child_ids}
    if not child_ids:
        return out
    lo = today - timedelta(days=days - 1)

    view_rows = db.execute(
        select(Event.child_id, Event.plan_item_id, Event.local_date, func.count())
        .join(PlanItem, PlanItem.id == Event.plan_item_id)
        .join(Plan, (Plan.id == PlanItem.plan_id) & (Plan.child_id == Event.child_id))
        .where(Event.child_id.in_(child_ids), Event.type == EventType.view_complete, Event.local_date <= today)
        .group_by(Event.child_id, Event.plan_item_id, Event.local_date)
        .order_by(Event.child_id, Event.plan_item_id, Event.local_date)
    ).all()
    for child_id, item_id, d, n in view_rows:
        out[child_id].views.append((item_id, d, n))

    seconds_rows = db.execute(
        select(Event.child_id, Event.type, Event.local_date, func.sum(Event.payload["seconds"].as_float()))
        .where(
            Event.child_id.in_(child_ids), Event.type.in_([EventType.watch_progress, EventType.time_extension]),
            Event.local_date >= lo, Event.local_date <= today,
        )
        .group_by(Event.child_id, Event.type, Event.local_date)
        .order_by(Event.child_id, Event.type, Event.local_date)
    ).all()
    for child_id, event_type, d, total in seconds_rows:
        target = out[child_id].watch_seconds if event_type is EventType.watch_progress else out[child_id].time_extension_seconds
        target.append((d, int(round(total or 0))))

    # A session's length is end - start of two ISO strings: no portable SQL, so sum the payloads of the window in Python.
    session_totals: dict[tuple[uuid.UUID, date], int] = {}
    for child_id, d, payload in db.execute(
        select(Event.child_id, Event.local_date, Event.payload)
        .where(
            Event.child_id.in_(child_ids), Event.type == EventType.app_session,
            Event.local_date >= lo, Event.local_date <= today,
        )
        .order_by(Event.id)
    ):  # validated and normalised at ingest: ISO-8601 start/end with end >= start
        start, end = datetime.fromisoformat(payload["start"]), datetime.fromisoformat(payload["end"])
        session_totals[(child_id, d)] = session_totals.get((child_id, d), 0) + max(0, int((end - start).total_seconds()))
    for (child_id, d), total in sorted(session_totals.items(), key=lambda kv: (str(kv[0][0]), kv[0][1])):
        out[child_id].app_seconds.append((d, total))
    return out


def load_engine_input(db: Session, child: Child, plan: Plan, today: date) -> EngineInput:
    rows = db.execute(
        select(PlanItem, Video)
        .join(Video, Video.id == PlanItem.video_id)
        .where(PlanItem.plan_id == plan.id)
        .order_by(PlanItem.position, PlanItem.id)
    ).all()

    items: list[dict[str, Any]] = []
    unavailable: list[str] = []
    videos: dict[str, Video] = {}
    for item, video in rows:
        videos[str(video.id)] = video
        if video.status is VideoStatus.unavailable and item.status is PlanItemStatus.active:
            unavailable.append(str(item.id))  # never offered; the parent is told instead
            continue
        items.append(
            ItemState(
                id=str(item.id), video_id=str(video.id), position=item.position, required=item.required,
                view_target=item.view_target, daily_cap=item.daily_cap, anchor_date=item.anchor_date,
                on_miss=item.on_miss.value, status=item.status.value, duration_seconds=video.duration_seconds,
                added_on=item.added_on,
            ).to_dict()
        )

    history = load_histories(db, [child.id], today)[child.id]
    known = {i["id"] for i in items}  # the engine ignores unknown ids anyway; unavailable items are simply not passed
    views = [
        {"item_id": str(item_id), "local_date": d.isoformat()}
        for item_id, d, n in history.views if str(item_id) in known for _ in range(n)
    ]
    catch_up_from = today - timedelta(days=CATCH_UP_WINDOW_DAYS)
    seconds = [{"local_date": d.isoformat(), "seconds": n} for d, n in history.watch_seconds if d >= catch_up_from]
    extension = sum(n for d, n in history.time_extension_seconds if d == today)
    limits = ChildLimits(child.daily_app_minutes, extension)
    app_seconds = [{"local_date": d.isoformat(), "seconds": n} for d, n in history.app_seconds if d == today]

    return EngineInput(
        today=today, plan=plan_config(plan).to_dict(), items=items, views=views, seconds=seconds,
        app_seconds=app_seconds, limits=limits.to_dict(),
        unavailable_item_ids=unavailable, videos=videos,
    )
